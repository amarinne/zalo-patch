package com.ez.zalopatch;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

final class ZaloRestart {
    private static final String TARGET_PACKAGE = "com.zing.zalo";

    interface Callback {
        void onResult(Result result);
    }

    enum Result {
        SENT,
        SENT_NO_ROOT,
        ROOT_DENIED,
        FAILED
    }

    private ZaloRestart() {
    }

    static void run(Context context, Callback callback) {
        Context appContext = context.getApplicationContext();
        new Thread(() -> {
            Result result = restartBlocking(appContext);
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(result));
        }, "zalo-restart").start();
    }

    private static Result restartBlocking(Context context) {
        // Best effort only: this is a cached fast-path for reads, not the source of truth. The
        // module's ContentProvider always reflects the live SharedPreferences values, so a failed
        // (unrooted) mirror write must not block relaunching the target app below.
        TweakStore.syncProperties(context);
        long changeGeneration = SettingsChanges.generation(context);
        Result rootResult = restartWithRoot(context, changeGeneration);
        if (rootResult != null) {
            return rootResult;
        }
        return restartWithoutRoot(context, changeGeneration);
    }

    /** Returns null when no root is available, so the caller can fall back. */
    private static Result restartWithRoot(Context context, long changeGeneration) {
        try {
            Process process = new ProcessBuilder(
                    "su", "-c",
                    "am force-stop com.zing.zalo && monkey -p com.zing.zalo -c android.intent.category.LAUNCHER 1")
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
                return null;
            }
            return Result.FAILED;
        } catch (java.io.IOException noSuBinary) {
            // No su binary at all: not a rooted device (e.g. a stock LSPatch install). Fall back.
            return null;
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }

    /**
     * LSPatch commonly runs without root (that is its main appeal over LSPosed/Magisk), so a
     * force-stop is not available. Kill whatever background copy exists and relaunch; a process
     * that is not currently in the foreground is killable this way. If Zalo is in the foreground,
     * the relaunch still brings it forward, but the running process keeps its already-hooked,
     * already-cached settings until the user manually closes it from Recents.
     */
    private static Result restartWithoutRoot(Context context, long changeGeneration) {
        try {
            Object systemService = context.getSystemService(Context.ACTIVITY_SERVICE);
            if (systemService instanceof ActivityManager) {
                ((ActivityManager) systemService).killBackgroundProcesses(TARGET_PACKAGE);
            }
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent == null) {
                return Result.FAILED;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(launchIntent);
            SettingsChanges.clearIfGeneration(context, changeGeneration);
            return Result.SENT_NO_ROOT;
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }
}
