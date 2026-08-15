package com.ez.zalopatch;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

public final class PromoNotificationListenerService extends NotificationListenerService {
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String FEATURE = "notifications.listener";
    private static final String OBSERVER_FEATURE = "notifications.listener_observer";
    private int observedCount;
    private int blockedCount;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        TweakStore.initialize(this);
        if (SettingsStore.getBoolean(this, Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS)) {
            mirror("installed_no_hits", "NotificationListenerService", "", "", 1, 0);
            mirror(OBSERVER_FEATURE, "installed_no_hits", "NotificationListenerService", "", "", 1, 0);
        } else {
            mirror("disabled", "NotificationListenerService", "", "", 0, 0);
            mirror(OBSERVER_FEATURE, "disabled", "NotificationListenerService", "", "", 0, 0);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !TARGET_PACKAGE.equals(sbn.getPackageName())) {
            return;
        }
        TweakStore.initialize(this);
        if (!SettingsStore.getBoolean(this, Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS)) {
            return;
        }
        boolean promo = NotificationPromoClassifier.isPromoNotification(sbn.getNotification());
        if (!promo) {
            observedCount++;
            mirror(OBSERVER_FEATURE, "active", "NotificationListenerService#onNotificationPosted",
                    NotificationPromoClassifier.notificationMetadata(sbn.getNotification()), "", 1, observedCount);
            return;
        }
        cancelNotification(sbn.getKey());
        blockedCount++;
        mirror("active", "NotificationListenerService#cancelNotification",
                NotificationPromoClassifier.notificationMetadata(sbn.getNotification()), "", 1, blockedCount);
    }

    private void mirror(String status, String target, String detail, String error, int installCount, int hitCount) {
        mirror(FEATURE, status, target, detail, error, installCount, hitCount);
    }

    private void mirror(String feature, String status, String target, String detail, String error, int installCount, int hitCount) {
        SelfCheckRegistry.submit(this, feature, status, target, installCount, hitCount,
                detail, error);
    }
}
