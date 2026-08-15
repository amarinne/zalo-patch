package com.ez.zalopatch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Owns the bounded debug-property lifecycle for guided capture. */
final class DiagnosticCaptureManager {
    private static final String PREFS = "diagnostic_capture_v1";

    private DiagnosticCaptureManager() {
    }

    static StartResult start(Context context, String category, String description) {
        if (!DiagnosticReportContract.validCategory(category)
                || !DiagnosticReportContract.validDescription(description)) {
            return StartResult.failure(StartFailure.INVALID_INPUT);
        }
        if (!cancel(context)) return StartResult.failure(StartFailure.RESTORE_FAILED);
        DiagnosticsState.clearRuntimeDiscoveryRequest(context);
        DiagnosticDraftStore.clear(context);
        DiagnosticRootProcessRunner runner = new DiagnosticRootProcessRunner();
        String rootStatus = DiagnosticCaptureCollector.checkRootAccess(runner);
        if (!"granted".equals(rootStatus)) {
            return StartResult.failure("denied".equals(rootStatus)
                    ? StartFailure.ROOT_DENIED : StartFailure.ROOT_ERROR);
        }
        Session session = new Session(
                DiagnosticReportContract.newReportId(), category, description,
                System.currentTimeMillis(), SystemClock.elapsedRealtime(),
                HookConfig.isDebugEnabled());
        if (!writeSession(context, session)) {
            return StartResult.failure(StartFailure.STATE_WRITE_FAILED);
        }
        scheduleTimeout(context, session.startedAtElapsedMs);
        if (!setDebugLogging(runner, true)) {
            if (setDebugLogging(runner, session.previousDebugLogging)) {
                clearSession(context);
                cancelTimeout(context);
                return StartResult.failure(StartFailure.DEBUG_ENABLE_FAILED);
            }
            return StartResult.failure(StartFailure.RESTORE_FAILED);
        }
        if ("compatibility".equals(category)
                && !SymbolSchema.active(context).valid
                && !DiagnosticsState.requestRuntimeDiscovery(context)) {
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
            setDebugLogging(runner, session.previousDebugLogging);
            clearSession(context);
            cancelTimeout(context);
            return StartResult.failure(StartFailure.STATE_WRITE_FAILED);
        }
        return StartResult.success(session);
    }

    static FinishedCapture finish(Context context) {
        Session session = readSession(context);
        if (session == null || expired(session, SystemClock.elapsedRealtime())) {
            expireIfNeeded(context);
            return null;
        }
        DiagnosticRootProcessRunner runner = new DiagnosticRootProcessRunner();
        DiagnosticCaptureCollector.CapturedData data =
                new DiagnosticCaptureCollector(runner).collect(session.startedAtWallMs);
        if (!setDebugLogging(runner, session.previousDebugLogging)) {
            return null;
        }
        if ("compatibility".equals(session.category)
                && !SymbolSchema.active(context).valid) {
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
        }
        clearSession(context);
        cancelTimeout(context);
        return new FinishedCapture(session, System.currentTimeMillis(), data);
    }

    static boolean cancel(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
            cancelTimeout(context);
            return true;
        }
        boolean previous = prefs.getBoolean(KEY_PREVIOUS_DEBUG, false);
        if (!setDebugLogging(new DiagnosticRootProcessRunner(), previous)) return false;
        if ("compatibility".equals(prefs.getString(KEY_CATEGORY, ""))) {
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
        }
        clearSession(context);
        cancelTimeout(context);
        DiagnosticDraftStore.clear(context);
        return true;
    }

    static boolean expireIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false;
        Session session = readSession(context);
        if (session != null && !expired(session, SystemClock.elapsedRealtime())) return false;
        boolean previous = prefs.getBoolean(KEY_PREVIOUS_DEBUG, false);
        if (!setDebugLogging(new DiagnosticRootProcessRunner(), previous)) return false;
        if ("compatibility".equals(prefs.getString(KEY_CATEGORY, ""))) {
            DiagnosticsState.clearRuntimeDiscoveryRequest(context);
        }
        clearSession(context);
        cancelTimeout(context);
        DiagnosticDraftStore.clear(context);
        return true;
    }

    static Session current(Context context) {
        return readSession(context);
    }

    static boolean expired(Session session, long nowElapsedMs) {
        return session == null || session.startedAtElapsedMs < 0L
                || nowElapsedMs < session.startedAtElapsedMs
                || nowElapsedMs - session.startedAtElapsedMs >= DiagnosticReportContract.TTL_MS;
    }

    private static boolean setDebugLogging(
            DiagnosticRootProcessRunner runner, boolean enabled) {
        DiagnosticRootProcessRunner.Result write = runner.run(
                "setprop debug.zalopatch " + (enabled ? "1" : "0"),
                DiagnosticReportContract.COMMAND_TIMEOUT_MS);
        if (!write.successful()) return false;
        DiagnosticRootProcessRunner.Result read = runner.run(
                "getprop debug.zalopatch", DiagnosticReportContract.COMMAND_TIMEOUT_MS);
        if (!read.successful()) return false;
        String value = lastNonBlankLine(read.output);
        return enabled ? "1".equals(value) || "true".equalsIgnoreCase(value)
                : !"1".equals(value) && !"true".equalsIgnoreCase(value);
    }

    private static String lastNonBlankLine(String value) {
        String result = "";
        if (value == null) return result;
        for (String line : value.split("\\r?\\n")) {
            if (!line.trim().isEmpty()) result = line.trim();
        }
        return result;
    }

    private static Session readSession(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return null;
        String reportId = prefs.getString(KEY_REPORT_ID, "");
        String category = prefs.getString(KEY_CATEGORY, "");
        String description = prefs.getString(KEY_DESCRIPTION, "");
        if (!DiagnosticReportContract.validReportId(reportId)
                || !DiagnosticReportContract.validCategory(category)
                || !DiagnosticReportContract.validDescription(description)) {
            return null;
        }
        return new Session(reportId, category, description,
                prefs.getLong(KEY_STARTED_WALL, -1L),
                prefs.getLong(KEY_STARTED_ELAPSED, -1L),
                prefs.getBoolean(KEY_PREVIOUS_DEBUG, false));
    }

    private static boolean writeSession(Context context, Session session) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_REPORT_ID, session.reportId)
                .putString(KEY_CATEGORY, session.category)
                .putString(KEY_DESCRIPTION, session.description)
                .putLong(KEY_STARTED_WALL, session.startedAtWallMs)
                .putLong(KEY_STARTED_ELAPSED, session.startedAtElapsedMs)
                .putBoolean(KEY_PREVIOUS_DEBUG, session.previousDebugLogging)
                .commit();
    }

    private static void clearSession(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void scheduleTimeout(Context context, long startedAtElapsedMs) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    startedAtElapsedMs + DiagnosticReportContract.TTL_MS,
                    timeoutIntent(context));
        }
    }

    private static void cancelTimeout(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms != null) alarms.cancel(timeoutIntent(context));
    }

    private static PendingIntent timeoutIntent(Context context) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(context, DiagnosticCaptureTimeoutReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    enum StartFailure {
        INVALID_INPUT,
        ROOT_DENIED,
        ROOT_ERROR,
        RESTORE_FAILED,
        STATE_WRITE_FAILED,
        DEBUG_ENABLE_FAILED
    }

    static final class StartResult {
        final Session session;
        final StartFailure failure;

        private StartResult(Session session, StartFailure failure) {
            this.session = session;
            this.failure = failure;
        }

        static StartResult success(Session session) {
            return new StartResult(session, null);
        }

        static StartResult failure(StartFailure failure) {
            return new StartResult(null, failure);
        }

        boolean successful() {
            return session != null;
        }
    }

    static final class Session {
        final String reportId;
        final String category;
        final String description;
        final long startedAtWallMs;
        final long startedAtElapsedMs;
        final boolean previousDebugLogging;

        Session(String reportId, String category, String description, long startedAtWallMs,
                long startedAtElapsedMs, boolean previousDebugLogging) {
            this.reportId = reportId;
            this.category = category;
            this.description = description;
            this.startedAtWallMs = startedAtWallMs;
            this.startedAtElapsedMs = startedAtElapsedMs;
            this.previousDebugLogging = previousDebugLogging;
        }
    }

    static final class FinishedCapture {
        final Session session;
        final long finishedAtWallMs;
        final DiagnosticCaptureCollector.CapturedData data;

        FinishedCapture(Session session, long finishedAtWallMs,
                        DiagnosticCaptureCollector.CapturedData data) {
            this.session = session;
            this.finishedAtWallMs = finishedAtWallMs;
            this.data = data;
        }
    }

    private static final String KEY_ACTIVE = "active";
    private static final String KEY_REPORT_ID = "report_id";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_DESCRIPTION = "description";
    private static final String KEY_STARTED_WALL = "started_wall";
    private static final String KEY_STARTED_ELAPSED = "started_elapsed";
    private static final String KEY_PREVIOUS_DEBUG = "previous_debug";
}
