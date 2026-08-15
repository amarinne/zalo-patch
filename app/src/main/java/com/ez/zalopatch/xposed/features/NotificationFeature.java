package com.ez.zalopatch.xposed.features;

import android.app.Notification;
import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.CallRecordingMetadata;
import com.ez.zalopatch.NotificationHistoryPayload;
import com.ez.zalopatch.NotificationPromoClassifier;
import com.ez.zalopatch.SelfCheckReceiver;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class NotificationFeature extends Feature {
    private static final String FEATURE = "notifications.promo";
    private static final String OBSERVER_FEATURE = "notifications.observer";
    private static final String HISTORY_FEATURE = "notifications.history";

    public NotificationFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "Notifications";
    }

    @Override
    public void doHook() {
        boolean filterEnabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS);
        boolean historyEnabled = HookConfig.isEnabled(Tweaks.KEY_RECORD_NOTIFICATION_HISTORY);
        boolean callMetadataEnabled = HookConfig.isEnabled(Tweaks.KEY_AUTO_RECORD_CALLS);
        if (!filterEnabled && !historyEnabled && !callMetadataEnabled) {
            SelfCheckRegistry.markDisabled(FEATURE, "NotificationManager.notify");
            SelfCheckRegistry.markDisabled(OBSERVER_FEATURE, "NotificationManager.notify");
            SelfCheckRegistry.markDisabled(HISTORY_FEATURE, "NotificationManager.notify");
            return;
        }
        try {
            Class<?> notificationManagerClass = android.app.NotificationManager.class;
            int hooked = 0;
            for (Method method : notificationManagerClass.getDeclaredMethods()) {
                if (!method.getName().startsWith("notify") || notificationArgIndex(method) < 0) {
                    continue;
                }
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Notification notification = notificationArg(param, method);
                        if (notification == null) {
                            return;
                        }
                        if (callMetadataEnabled) {
                            CallRecordingMetadata.observe(notification);
                        }
                        String metadata = NotificationPromoClassifier.notificationMetadata(notification);
                        boolean promo = NotificationPromoClassifier.isPromoNotification(notification);
                        if (historyEnabled) {
                            recordHistory(param, method, notification, promo, filterEnabled && promo, metadata);
                        }
                        if (!filterEnabled) {
                            return;
                        }
                        if (!promo) {
                            SelfCheckRegistry.incrementHit(OBSERVER_FEATURE, "NotificationManager#" + method.getName(), metadata);
                            return;
                        }
                        param.setResult(null);
                        SelfCheckRegistry.markSuppressed(FEATURE, "NotificationManager#" + method.getName(), metadata);
                        log("Blocked promo notification: " + metadata);
                    }
                });
                hooked++;
            }
            if (hooked > 0) {
                if (filterEnabled) {
                    SelfCheckRegistry.markInstalled(FEATURE, "NotificationManager.notify", hooked);
                    SelfCheckRegistry.markInstalled(OBSERVER_FEATURE, "NotificationManager.notify", hooked);
                } else {
                    SelfCheckRegistry.markDisabled(FEATURE, "NotificationManager.notify");
                    SelfCheckRegistry.markDisabled(OBSERVER_FEATURE, "NotificationManager.notify");
                }
                if (historyEnabled) {
                    SelfCheckRegistry.markInstalled(HISTORY_FEATURE, "NotificationManager.notify", hooked);
                } else {
                    SelfCheckRegistry.markDisabled(HISTORY_FEATURE, "NotificationManager.notify");
                }
            } else {
                SelfCheckRegistry.markStale(FEATURE, "NotificationManager.notify", "no notify methods");
                SelfCheckRegistry.markStale(OBSERVER_FEATURE, "NotificationManager.notify", "no notify methods");
                SelfCheckRegistry.markStale(HISTORY_FEATURE, "NotificationManager.notify", "no notify methods");
            }
            log("Notification promo filter installed -> " + hooked + " methods");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE, "NotificationManager.notify", throwable);
            SelfCheckRegistry.markFailed(OBSERVER_FEATURE, "NotificationManager.notify", throwable);
            SelfCheckRegistry.markFailed(HISTORY_FEATURE, "NotificationManager.notify", throwable);
            log("Failed to hook notifications: " + throwable);
        }
    }

    private void recordHistory(XC_MethodHook.MethodHookParam param, Method method, Notification notification, boolean promo, boolean cancelled, String metadata) {
        try {
            Context context = HookConfig.resolveModuleContextForHooks();
            if (context == null) {
                SelfCheckRegistry.markFailed(HISTORY_FEATURE, "NotificationManager#" + method.getName(),
                        new IllegalStateException("module context unavailable"));
                return;
            }
            android.content.ContentValues values = NotificationHistoryPayload.fromNotification(
                    notificationKey(param), notification, promo, cancelled);
            android.os.Bundle request = new android.os.Bundle();
            request.putParcelable("values", values);
            android.os.Bundle response = context.getContentResolver().call(
                    android.net.Uri.parse("content://com.ez.zalopatch.config"),
                    "record_notification_history", null, request);
            long id = response == null ? -1L : response.getLong("id", -1L);
            if (id < -2L || id == -1L) {
                sendHistoryFallback(context, method, values, metadata);
                return;
            }
            SelfCheckRegistry.markSuppressed(HISTORY_FEATURE, "NotificationManager#" + method.getName(), metadata);
        } catch (Throwable throwable) {
            try {
                Context context = HookConfig.resolveModuleContextForHooks();
                if (context == null) throw throwable;
                android.content.ContentValues values = NotificationHistoryPayload.fromNotification(
                        notificationKey(param), notification, promo, cancelled);
                sendHistoryFallback(context, method, values, metadata);
            } catch (Throwable fallbackFailure) {
                SelfCheckRegistry.markFailed(HISTORY_FEATURE,
                        "NotificationManager#" + method.getName(), fallbackFailure);
            }
        }
    }

    private static void sendHistoryFallback(
            Context context, Method method, android.content.ContentValues values, String metadata) {
        if (android.os.Build.VERSION.SDK_INT < 34) {
            throw new IllegalStateException("notification history provider unavailable");
        }
        android.content.Intent intent = new android.content.Intent(
                SelfCheckReceiver.ACTION_RECORD_NOTIFICATION_HISTORY);
        intent.setComponent(new android.content.ComponentName("com.ez.zalopatch",
                "com.ez.zalopatch.SelfCheckReceiver"));
        intent.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        intent.putExtra(SelfCheckReceiver.EXTRA_VALUES, values);
        intent.putExtra("target", "NotificationManager#" + method.getName());
        intent.putExtra("detail", metadata);
        android.app.BroadcastOptions options = android.app.BroadcastOptions.makeBasic();
        options.setShareIdentityEnabled(true);
        context.sendBroadcast(intent, null, options.toBundle());
    }

    private static String notificationKey(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Object arg : param.args) {
            if (arg == null || arg instanceof Notification) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('|');
            }
            if (arg instanceof CharSequence || arg instanceof Number || arg instanceof Boolean) {
                builder.append(arg);
            } else {
                builder.append(arg.getClass().getSimpleName());
            }
        }
        return builder.toString();
    }

    private static int notificationArgIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (Notification.class.isAssignableFrom(types[i])) {
                return i;
            }
        }
        return -1;
    }

    private static Notification notificationArg(XC_MethodHook.MethodHookParam param, Method method) {
        int index = notificationArgIndex(method);
        if (index < 0 || param.args == null || index >= param.args.length || !(param.args[index] instanceof Notification)) {
            return null;
        }
        return (Notification) param.args[index];
    }
}
