package com.ez.zalopatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

final class ZaloRestart {
    interface Callback {
        void onResult(Result result);
    }

    enum Result {
        SENT,
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
        try {
            long changeGeneration = SettingsChanges.generation(context);
            if (!TweakStore.syncProperties(context)) {
                return Result.ROOT_DENIED;
            }
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
                return Result.ROOT_DENIED;
            }
            return Result.FAILED;
        } catch (Exception ignored) {
            return Result.FAILED;
        }
    }
}
