package com.ez.zalopatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class ZaloRestart {
    interface Callback {
        void onResult(Result result);
    }

    enum Result {
        SENT,
        ROOT_DENIED,
        FAILED
    }

    /** Which Zalo app instance(s) to restart. Null user list means the legacy path. */
    static final class Target {
        private final List<Integer> userIds;

        private Target(List<Integer> userIds) {
            this.userIds = userIds == null
                    ? null : Collections.unmodifiableList(new ArrayList<>(userIds));
        }

        static Target single(int userId) {
            return new Target(Collections.singletonList(userId));
        }

        static Target all(List<Integer> userIds) {
            return new Target(userIds);
        }

        boolean isSingle() {
            return userIds != null && userIds.size() == 1;
        }

        int singleUserId() {
            return userIds.get(0);
        }

        List<Integer> userIds() {
            return userIds;
        }
    }

    private ZaloRestart() {
    }

    static void run(Context context, Callback callback) {
        run(context, null, callback);
    }

    static void run(Context context, Target target, Callback callback) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            Result result = restartBlocking(appContext, target);
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }, "zalo-restart").start();
    }

    /**
     * Lists running Zalo instances (one per Android user). Blocking; call off the main thread.
     * Returns an empty list when none runs or the probe fails — callers fall back to the
     * legacy restart, which is also the whole behavior when at most one instance runs.
     */
    static List<ZaloInstance> probeInstances(DiagnosticRootProcessRunner runner) {
        DiagnosticRootProcessRunner.Result first =
                runner.run(ZaloInstance.probeCommand(), DiagnosticReportContract.COMMAND_TIMEOUT_MS);
        if (first.timedOut || first.exitCode != 0) return Collections.emptyList();
        String output = first.output;
        int marker = output.indexOf(ZaloInstance.USERS_MARKER);
        String psOutput = marker < 0 ? output : output.substring(0, marker);
        String usersOutput = marker < 0 ? "" : output.substring(marker);
        Map<Integer, List<Integer>> grouped = ZaloInstance.parsePs(psOutput);
        if (grouped.isEmpty()) return Collections.emptyList();
        Map<Integer, String> labels = ZaloInstance.parseUsers(usersOutput);
        List<Integer> mainPids = new ArrayList<>();
        for (List<Integer> pids : grouped.values()) {
            if (pids != null && !pids.isEmpty()) mainPids.add(Collections.min(pids));
        }
        Map<Integer, Integer> oomScores = Collections.emptyMap();
        if (!mainPids.isEmpty()) {
            DiagnosticRootProcessRunner.Result oom = runner.run(
                    ZaloInstance.oomCommand(mainPids),
                    DiagnosticReportContract.COMMAND_TIMEOUT_MS);
            if (!oom.timedOut && oom.exitCode == 0) {
                oomScores = ZaloInstance.parseOomScores(oom.output);
            }
        }
        return ZaloInstance.combine(grouped, labels, oomScores);
    }

    private static Result restartBlocking(Context context, Target target) {
        try {
            long changeGeneration = SettingsChanges.generation(context);
            if (!TweakStore.syncProperties(context)) {
                return Result.ROOT_DENIED;
            }
            String command = target == null ? legacyCommand()
                    : target.isSingle() ? ZaloInstance.commandForUser(target.singleUserId())
                    : ZaloInstance.commandForAll(target.userIds());
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                SettingsChanges.clearIfGeneration(context, changeGeneration);
                return Result.SENT;
            }
            String message = output.toString().toLowerCase(Locale.US);
            if (message.contains("denied") || message.contains("not allowed")
                    || message.contains("permission")) {
                return Result.ROOT_DENIED;
            }
            return Result.FAILED;
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }

    /** Frozen legacy behavior for the 0–1 instance path; do not modernize. */
    static String legacyCommand() {
        return "launcher=$(cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER com.zing.zalo | tail -n 1) "
                + "&& [ \"${launcher#com.zing.zalo/}\" != \"$launcher\" ] "
                + "&& am force-stop com.zing.zalo "
                + "&& am start -n \"$launcher\"";
    }
}
