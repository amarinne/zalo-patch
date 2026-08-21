package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/** Cached, explicit root capability probe for module-process actions. */
final class RootAccess {
    enum State {
        GRANTED,
        DENIED,
        ABSENT;

        String reportValue() {
            return this == ABSENT ? "error" : name().toLowerCase(java.util.Locale.US);
        }
    }

    interface Callback {
        void onResult(State state);
    }

    private static final String KEY_STATE = "capability.root_access.state";
    private static final String KEY_MODULE_VERSION = "capability.root_access.module_version";
    private static final Object PROBE_LOCK = new Object();
    private static final List<Callback> ACTIVE_CALLBACKS = new ArrayList<>();
    private static final List<Callback> FORCED_CALLBACKS = new ArrayList<>();
    private static boolean probing;
    private static boolean activeProbeForced;
    private static boolean forcedProbeQueued;

    private RootAccess() {
    }

    static State cached(Context context) {
        SharedPreferences prefs = preferences(context);
        if (prefs.getInt(KEY_MODULE_VERSION, -1) != BuildConfig.VERSION_CODE) {
            return State.ABSENT;
        }
        return parse(prefs.getString(KEY_STATE, ""));
    }

    static boolean hasFreshCache(Context context) {
        SharedPreferences prefs = preferences(context);
        return prefs.getInt(KEY_MODULE_VERSION, -1) == BuildConfig.VERSION_CODE
                && !prefs.getString(KEY_STATE, "").isEmpty();
    }

    static State getOrProbe(Context context) {
        if (hasFreshCache(context)) return cached(context);
        return probeAndStore(context, new DiagnosticRootProcessRunner());
    }

    static void probeIfNeeded(Context context, Callback callback) {
        if (hasFreshCache(context)) {
            deliver(callback, cached(context));
            return;
        }
        probeAsync(context, false, callback);
    }

    static void recheck(Context context, Callback callback) {
        probeAsync(context, true, callback);
    }

    static State probe(DiagnosticRootProcessRunner runner) {
        return classify(runner.run("id -u", DiagnosticReportContract.COMMAND_TIMEOUT_MS));
    }

    static State classify(DiagnosticRootProcessRunner.Result result) {
        if (!result.timedOut && result.exitCode == 0 && "0".equals(result.output.trim())) {
            return State.GRANTED;
        }
        if (!result.timedOut && result.exitCode < 0 && result.output.trim().isEmpty()) {
            return State.ABSENT;
        }
        return State.DENIED;
    }

    static java.util.Map<String, ?> snapshotForTest(Context context) {
        return new java.util.HashMap<>(preferences(context).getAll());
    }

    static void restoreForTest(Context context, java.util.Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences(context).edit().clear();
        if (values != null) {
            for (java.util.Map.Entry<String, ?> entry : values.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
                else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
                else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
                else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            }
        }
        editor.commit();
    }

    static void setForTest(Context context, State state) {
        preferences(context).edit()
                .putString(KEY_STATE, state.name())
                .putInt(KEY_MODULE_VERSION, BuildConfig.VERSION_CODE)
                .commit();
    }

    private static void probeAsync(Context context, boolean force, Callback callback) {
        Context appContext = context.getApplicationContext();
        Context safeContext = appContext == null ? context : appContext;
        if (!force && hasFreshCache(safeContext)) {
            deliver(callback, cached(safeContext));
            return;
        }
        synchronized (PROBE_LOCK) {
            if (probing) {
                if (force && !activeProbeForced) {
                    forcedProbeQueued = true;
                    if (callback != null) FORCED_CALLBACKS.add(callback);
                } else if (callback != null) {
                    ACTIVE_CALLBACKS.add(callback);
                }
                return;
            }
            probing = true;
            activeProbeForced = force;
            if (callback != null) ACTIVE_CALLBACKS.add(callback);
        }
        startProbe(safeContext);
    }

    private static void startProbe(Context context) {
        new Thread(() -> {
            State state = State.ABSENT;
            try {
                state = probeAndStore(context, new DiagnosticRootProcessRunner());
            } catch (Throwable ignored) {
            }
            List<Callback> completedCallbacks;
            boolean runForcedProbe;
            synchronized (PROBE_LOCK) {
                completedCallbacks = new ArrayList<>(ACTIVE_CALLBACKS);
                ACTIVE_CALLBACKS.clear();
                runForcedProbe = forcedProbeQueued;
                if (runForcedProbe) {
                    forcedProbeQueued = false;
                    activeProbeForced = true;
                    ACTIVE_CALLBACKS.addAll(FORCED_CALLBACKS);
                    FORCED_CALLBACKS.clear();
                } else {
                    probing = false;
                    activeProbeForced = false;
                }
            }
            for (Callback completedCallback : completedCallbacks) {
                deliver(completedCallback, state);
            }
            if (runForcedProbe) startProbe(context);
        }, "zalo-root-capability").start();
    }

    private static State probeAndStore(
            Context context, DiagnosticRootProcessRunner runner) {
        State state = probe(runner);
        preferences(context).edit()
                .putString(KEY_STATE, state.name())
                .putInt(KEY_MODULE_VERSION, BuildConfig.VERSION_CODE)
                .commit();
        return state;
    }

    private static State parse(String value) {
        try {
            return State.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return State.ABSENT;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(UiSettings.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void deliver(Callback callback, State state) {
        if (callback == null) return;
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(state));
    }
}
