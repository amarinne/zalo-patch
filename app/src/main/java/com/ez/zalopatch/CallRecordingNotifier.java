package com.ez.zalopatch;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

final class CallRecordingNotifier {
    private static final String FEATURE = "calls.auto_record.notifications";
    private static final String STATUS_CHANNEL = "call_recording_status";
    private static final String RESULT_CHANNEL = "call_recording_result";
    private static final int STATUS_ID = 4601;
    private static final int RESULT_ID = 4602;

    private CallRecordingNotifier() {
    }

    static boolean showRunning(Context context) {
        context = UiSettings.localizedContext(context);
        if (!enabled(context)) {
            cancelStatus(context);
            return false;
        }
        if (!canPost(context)) {
            updateSelfCheck(context, "failed", "permission", false,
                    "Notification permission unavailable");
            return false;
        }
        NotificationManager manager = manager(context);
        ensureChannels(context, manager);
        manager.notify(STATUS_ID, statusBuilder(context, STATUS_CHANNEL)
                .setContentTitle(context.getString(R.string.zp_call_recording_notification_running))
                .setContentText(context.getString(R.string.zp_call_recording_notification_running_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
        updateSelfCheck(context, "active", "running", true, "");
        return true;
    }

    static void showFinalizing(Context context) {
        context = UiSettings.localizedContext(context);
        if (!hasActiveStatus(context)) {
            return;
        }
        if (!enabled(context) || !canPost(context)) {
            cancelStatus(context);
            return;
        }
        NotificationManager manager = manager(context);
        ensureChannels(context, manager);
        manager.notify(STATUS_ID, statusBuilder(context, STATUS_CHANNEL)
                .setContentTitle(context.getString(R.string.zp_call_recording_notification_saving))
                .setContentText(context.getString(R.string.zp_call_recording_notification_saving_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build());
        updateSelfCheck(context, "active", "finalizing", false, "");
    }

    static void finishSaved(Context context) {
        context = UiSettings.localizedContext(context);
        boolean hadStatus = hasActiveStatus(context);
        cancelStatus(context);
        if (!hadStatus || !enabled(context) || !canPost(context)) {
            return;
        }
        NotificationManager manager = manager(context);
        ensureChannels(context, manager);
        manager.notify(RESULT_ID, resultBuilder(context, RESULT_CHANNEL)
                .setContentTitle(context.getString(R.string.zp_call_recording_notification_saved))
                .setContentText(context.getString(R.string.zp_call_recording_notification_saved_text))
                .build());
        updateSelfCheck(context, "active", "saved", true, "");
    }

    static void finishFailed(Context context) {
        context = UiSettings.localizedContext(context);
        boolean hadStatus = hasActiveStatus(context);
        cancelStatus(context);
        if (!hadStatus || !enabled(context) || !canPost(context)) {
            return;
        }
        NotificationManager manager = manager(context);
        ensureChannels(context, manager);
        manager.notify(RESULT_ID, resultBuilder(context, RESULT_CHANNEL)
                .setContentTitle(context.getString(R.string.zp_call_recording_notification_failed))
                .setContentText(context.getString(R.string.zp_call_recording_notification_failed_text))
                .build());
        updateSelfCheck(context, "failed", "failed", false,
                "Recording finalization failed");
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = manager(context);
        return manager != null && manager.areNotificationsEnabled();
    }

    private static boolean enabled(Context context) {
        return TweakStore.isEnabled(context, Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS);
    }

    private static void cancelStatus(Context context) {
        NotificationManager manager = manager(context);
        if (manager != null) {
            manager.cancel(STATUS_ID);
        }
    }

    private static boolean hasActiveStatus(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null) {
            return false;
        }
        try {
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (notification.getId() == STATUS_ID) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static Notification.Builder statusBuilder(Context context, String channel) {
        return builder(context, channel)
                .setSmallIcon(R.drawable.ic_zp_recording)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent(context))
                .setColor(context.getColor(R.color.zp_status_active))
                .setWhen(System.currentTimeMillis());
    }

    private static Notification.Builder resultBuilder(Context context, String channel) {
        return builder(context, channel)
                .setSmallIcon(R.drawable.ic_zp_recording)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(contentIntent(context))
                .setAutoCancel(true)
                .setColor(context.getColor(R.color.zp_status_active))
                .setWhen(System.currentTimeMillis());
    }

    private static Notification.Builder builder(Context context, String channel) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channel)
                : new Notification.Builder(context);
    }

    private static PendingIntent contentIntent(Context context) {
        Intent intent = StatusActivity.routeIntent(context, StatusActivity.ROUTE_RECORDINGS);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    private static void ensureChannels(Context context, NotificationManager manager) {
        if (manager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel status = new NotificationChannel(STATUS_CHANNEL,
                context.getString(R.string.zp_call_recording_notification_status_channel),
                NotificationManager.IMPORTANCE_LOW);
        status.setDescription(context.getString(
                R.string.zp_call_recording_notification_status_channel_summary));
        status.setSound(null, null);
        status.enableVibration(false);
        manager.createNotificationChannel(status);

        NotificationChannel result = new NotificationChannel(RESULT_CHANNEL,
                context.getString(R.string.zp_call_recording_notification_result_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription(context.getString(
                R.string.zp_call_recording_notification_result_channel_summary));
        manager.createNotificationChannel(result);
    }

    private static void updateSelfCheck(
            Context context,
            String status,
            String state,
            boolean incrementHit,
            String error) {
        android.content.SharedPreferences preferences = TweakStore.preferences(context);
        String prefix = "selfcheck." + FEATURE + ".";
        int hits = preferences.getInt(prefix + "hit_count", 0);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        editor.putString(prefix + "status", status);
        editor.putString(prefix + "target", "NotificationManager");
        editor.putString(prefix + "detail", state.isEmpty() ? "" : "state=" + state);
        editor.putString(prefix + "error", error == null ? "" : error);
        editor.putInt(prefix + "install_count", 1);
        editor.putInt(prefix + "hit_count", incrementHit ? hits + 1 : hits);
        editor.putLong(prefix + "updated_at", System.currentTimeMillis());
        editor.apply();
    }
}
