package com.ez.zalopatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class ZaloRestart {
    interface Callback {
        void onResult(Result result);
    }

    enum Result {
        SENT,
        MANUAL,
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

    /**
     * Pure captured target identity: one installed package plus the module process's Android
     * user. Immutable, so a later UI selection cannot retarget an in-flight action.
     */
    static final class SelectedTarget {
        final String packageName;
        final int androidUserId;

        SelectedTarget(String packageName, int androidUserId) {
            if (!ZaloInstance.validPackage(packageName) || androidUserId < 0) {
                throw new IllegalArgumentException("Invalid Zalo restart target");
            }
            this.packageName = packageName;
            this.androidUserId = androidUserId;
        }

        /**
         * Rechecks registration, the module's current user and an optional passed scoped
         * context. A non-null {@code scopedPackage} must name this exact package.
         */
        boolean valid(Set<String> registered, int moduleUserId, String scopedPackage) {
            if (androidUserId != moduleUserId) return false;
            if (!HostPackages.allows(registered == null ? Collections.emptySet() : registered,
                    packageName)) {
                return false;
            }
            return scopedPackage == null || packageName.equals(scopedPackage);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof SelectedTarget)) return false;
            SelectedTarget target = (SelectedTarget) other;
            return androidUserId == target.androidUserId && packageName.equals(target.packageName);
        }

        @Override public int hashCode() {
            return 31 * packageName.hashCode() + androidUserId;
        }

        @Override public String toString() {
            return packageName + "@" + androidUserId;
        }
    }

    /**
     * One selected same-user target captured before asynchronous work. The scoped context is
     * retained with the target so publication and restart always address the same package.
     */
    static final class InstanceTarget {
        final SelectedTarget selection;
        private final Context moduleContext;
        private final Context context;

        private InstanceTarget(SelectedTarget selection, Context moduleContext, Context context) {
            this.selection = selection;
            this.moduleContext = moduleContext;
            this.context = context;
        }

        Context context() {
            return context;
        }

        /** Revalidates grammar, registration, own user and the retained scoped context. */
        boolean valid() {
            if (moduleContext == null || context == null) return false;
            try {
                int moduleUserId = ZaloInstance.androidUser(Process.myUid());
                String scopedPackage = HostPackages.renamed(context)
                        ? HostPackages.target(context) : null;
                return selection.valid(HostPackages.additional(moduleContext), moduleUserId,
                        scopedPackage)
                        && selection.packageName.equals(HostPackages.target(context));
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    private ZaloRestart() {
    }

    static void run(Context context, Callback callback) {
        run(context, null, callback);
    }

    static void run(Context context, Target target, Callback callback) {
        Context appContext = context.getApplicationContext();
        if (!running.compareAndSet(false, true)) {
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(Result.FAILED));
            return;
        }
        new Thread(() -> {
            Result result;
            try { result = restartBlocking(appContext, target); }
            catch (Throwable failure) { result = Result.FAILED; }
            finally { running.set(false); }
            Result completed = result;
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(completed));
        }, "zalo-restart").start();
    }

    /**
     * Captures one registered package in the module process's Android user. Returns null when
     * the package, registration, user or passed scoped context does not agree.
     */
    static InstanceTarget captureTarget(Context moduleContext, String packageName) {
        if (moduleContext == null || packageName == null) return null;
        try {
            Context module = HostPackages.base(moduleContext);
            Context app = module.getApplicationContext();
            if (app != null) module = app;
            int moduleUserId = ZaloInstance.androidUser(Process.myUid());
            String passedScopedPackage = HostPackages.renamed(moduleContext)
                    ? HostPackages.target(moduleContext) : null;
            SelectedTarget selection = new SelectedTarget(packageName, moduleUserId);
            if (!selection.valid(HostPackages.additional(module), moduleUserId,
                    passedScopedPackage)) {
                return null;
            }
            Context scoped = HostPackages.scoped(module, packageName);
            if (!packageName.equals(HostPackages.target(scoped))) return null;
            return new InstanceTarget(selection, module, scoped);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /** Package-aware restart for a selected same-user target. */
    static void runScoped(Context moduleContext, String packageName, Callback callback) {
        InstanceTarget target = captureTarget(moduleContext, packageName);
        if (target == null) {
            post(callback, Result.FAILED);
            return;
        }
        runScoped(target, callback);
    }

    static void runScoped(InstanceTarget target, Callback callback) {
        if (target == null) {
            post(callback, Result.FAILED);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            post(callback, Result.FAILED);
            return;
        }
        new Thread(() -> {
            Result result;
            try { result = restartScopedBlocking(target); }
            catch (Throwable failure) { result = Result.FAILED; }
            finally { running.set(false); }
            post(callback, result);
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
            if (!TweakStore.syncProperties(context)) return Result.FAILED;
            String command = target == null ? legacyCommand()
                    : target.isSingle() ? ZaloInstance.commandForUser(target.singleUserId())
                    : ZaloInstance.commandForAll(target.userIds());
            return execute(command);
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }

    private static Result restartScopedBlocking(InstanceTarget target) {
        try {
            if (!target.valid()) return Result.FAILED;
            if (!TweakStore.syncProperties(target.context())) return Result.FAILED;
            // Registration or the process user may change while publication runs. Never build
            // the shell command from the earlier check alone.
            if (!target.valid()) return Result.FAILED;
            return execute(ZaloInstance.commandForPackage(
                    target.selection.packageName, target.selection.androidUserId));
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }

    private static Result execute(String command) {
        DiagnosticRootProcessRunner.Result commandResult = new DiagnosticRootProcessRunner().run(
                command, DiagnosticReportContract.COMMAND_TIMEOUT_MS);
        if (commandResult.successful()) return Result.SENT;
        String message = commandResult.output.toLowerCase(Locale.US);
        if (message.contains("denied") || message.contains("not allowed")
                || message.contains("permission")) {
            return Result.ROOT_DENIED;
        }
        return Result.FAILED;
    }

    private static void post(Callback callback, Result result) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
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
