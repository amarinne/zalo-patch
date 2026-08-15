package com.ez.zalopatch;

import android.app.Notification;
import android.content.ContentValues;
import android.os.Bundle;

public final class NotificationHistoryPayload {
    private NotificationHistoryPayload() {
    }

    public static ContentValues fromNotification(String key, Notification notification, boolean promo, boolean cancelled) {
        ContentValues values = new ContentValues();
        if (notification == null) {
            return values;
        }
        Bundle extras = notification.extras;
        values.put("posted_at", System.currentTimeMillis());
        values.put("sbn_key", safe(key));
        values.put("channel_id", safe(NotificationPromoClassifier.channelId(notification)));
        values.put("category", safe(notification.category));
        values.put("template", template(extras));
        values.put("title", charSequence(extras, Notification.EXTRA_TITLE));
        values.put("text", charSequence(extras, Notification.EXTRA_TEXT));
        values.put("big_text", charSequence(extras, Notification.EXTRA_BIG_TEXT));
        values.put("lines", lines(extras));
        values.put("ticker", notification.tickerText == null ? "" : notification.tickerText.toString());
        values.put("metadata", NotificationPromoClassifier.notificationMetadata(notification));
        values.put("promo", promo ? 1 : 0);
        values.put("cancelled", cancelled ? 1 : 0);
        return values;
    }

    private static String charSequence(Bundle extras, String key) {
        if (extras == null) {
            return "";
        }
        CharSequence value = extras.getCharSequence(key);
        return value == null ? "" : value.toString();
    }

    private static String lines(Bundle extras) {
        if (extras == null) {
            return "";
        }
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines == null || lines.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (CharSequence line : lines) {
            if (line == null) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private static String template(Bundle extras) {
        if (extras == null) {
            return "";
        }
        String template = extras.getString(Notification.EXTRA_TEMPLATE);
        if (template == null) {
            return "";
        }
        int lastDot = template.lastIndexOf('.');
        return lastDot >= 0 ? template.substring(lastDot + 1) : template;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
