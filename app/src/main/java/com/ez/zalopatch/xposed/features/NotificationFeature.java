package com.ez.zalopatch.xposed.features;

import android.app.Notification;
import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.CallRecordingMetadata;
import com.ez.zalopatch.NotificationHistoryPayload;
import com.ez.zalopatch.NotificationPromoClassifier;
import com.ez.zalopatch.NotificationRuleStore;
import com.ez.zalopatch.SelfCheckReceiver;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;

import com.ez.zalopatch.xposed.core.XpHooks;

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
        Context fixtureContext = HookConfig.resolveFallbackContextForHooks();
        if (fixtureContext != null) NotificationE2eFixture.cleanupOrphans(fixtureContext);
        if (HookConfig.isDebugEnabled()) {
            // The OFF baseline still needs the fixed end-to-end notification fixture.
            try {
                hookBehaveProbe(historyEnabled, callMetadataEnabled);
                SelfCheckRegistry.markInstalled(OBSERVER_FEATURE, BEHAVE_ACTION, 1);
            } catch (Throwable throwable) {
                SelfCheckRegistry.markStale(OBSERVER_FEATURE, BEHAVE_ACTION,
                        throwable.getClass().getSimpleName());
            }
        }
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
                XpHooks.hookMethod(FEATURE, method, new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
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

    /** Test-only probe action for headless verification (`zalo-verify behave notifications`). */
    static final String BEHAVE_ACTION = "com.ez.zalopatch.behave.NOTIFICATION_PROBE";

    /**
     * Runs the live classifier over a synthetic notification and reports the verdict on
     * the existing observer row. When the device carries a custom keyword rule, the
     * fixture contains it and a promo verdict is expected; otherwise the run still
     * proves the probe path with a plain verdict. Read-only: nothing is posted,
     * stored, or filtered.
     */
    private void hookBehaveProbe(boolean historyEnabledAtHookInstall,
                                 boolean callMetadataEnabledAtHookInstall) throws Throwable {
        Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) {
            throw new IllegalStateException("application context unavailable");
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, android.content.Intent intent) {
                if (intent != null && NotificationE2eFixture.ACTION.equals(intent.getAction())) {
                    if (HookConfig.isDebugEnabled() && NotificationE2eFixture.isDebugCurrentlyEnabled()) {
                        NotificationE2eFixture.receive(context, intent,
                                historyEnabledAtHookInstall, callMetadataEnabledAtHookInstall, goAsync());
                    }
                    return;
                }
                if (!HookConfig.isDebugCurrentlyEnabled() || intent == null
                        || !BEHAVE_ACTION.equals(intent.getAction())) {
                    return;
                }
                String keyword = firstCustomKeyword();
                Notification test = buildProbeNotification(context, keyword);
                String detail;
                try {
                    boolean promo = NotificationPromoClassifier.isPromoNotification(test);
                    detail = keyword == null
                            ? "rules=none promo=" + promo
                            : "keyword=yes promo=" + promo;
                } catch (Throwable throwable) {
                    detail = "probe=threw:" + throwable.getClass().getSimpleName();
                }
                SelfCheckRegistry.markSuppressed(OBSERVER_FEATURE, "behave:notifications", detail);
            }
        };
        // Shell broadcasts cross UIDs. The E2E action accepts only a bounded run ID,
        // posts fixed notifications, and cleans only its own tags and new channels.
        android.content.IntentFilter filter = new android.content.IntentFilter(BEHAVE_ACTION);
        filter.addAction(NotificationE2eFixture.ACTION);
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private static String firstCustomKeyword() {
        try {
            NotificationRuleStore.RuleSet rules = HookConfig.notificationRules();
            if (rules == null) {
                return null;
            }
            for (String keyword : rules.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST)) {
                if (keyword != null && !keyword.trim().isEmpty()) {
                    return keyword.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Notification buildProbeNotification(Context context, String keyword) {
        String trailer = keyword == null ? "routine check-in" : "featuring " + keyword;
        try {
            return new Notification.Builder(context, "behave_probe")
                    .setContentTitle("Behave probe " + trailer)
                    .setContentText("Synthetic classifier fixture " + trailer)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .build();
        } catch (Throwable ignored) {
            Notification bare = new Notification();
            android.os.Bundle extras = bare.extras != null
                    ? bare.extras : new android.os.Bundle();
            extras.putCharSequence(Notification.EXTRA_TITLE, "Behave probe " + trailer);
            extras.putCharSequence(Notification.EXTRA_TEXT, "Synthetic classifier fixture");
            return bare;
        }
    }

    private void recordHistory(XpHooks.HookParam param, Method method, Notification notification, boolean promo, boolean cancelled, String metadata) {
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

    private static String notificationKey(XpHooks.HookParam param) {
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

    private static Notification notificationArg(XpHooks.HookParam param, Method method) {
        int index = notificationArgIndex(method);
        if (index < 0 || param.args == null || index >= param.args.length || !(param.args[index] instanceof Notification)) {
            return null;
        }
        return (Notification) param.args[index];
    }
}
