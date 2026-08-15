package com.ez.zalopatch;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;

import java.text.Normalizer;
import java.util.Locale;

public final class NotificationPromoClassifier {
    private NotificationPromoClassifier() {
    }

    public static boolean isPromoNotification(Notification notification) {
        if (notification == null) {
            return false;
        }
        return isPromo(channelId(notification), notificationTitle(notification),
                notificationText(notification), HookConfig.notificationRules());
    }

    static boolean isPromo(String channelId, String title, String content) {
        return isPromo(channelId, title, content, NotificationRuleStore.RuleSet.empty());
    }

    static boolean isPromo(
            String channelId,
            String title,
            String content,
            NotificationRuleStore.RuleSet rules) {
        String normalizedTitle = normalized(title);
        String text = normalized(joinContent(title, content));
        if (hasOperationalSafeChannel(channelId)) {
            return false;
        }
        NotificationRuleStore.RuleSet safeRules = rules == null
                ? NotificationRuleStore.RuleSet.empty() : rules;
        if (matchesAccount(normalizedTitle, safeRules.list(NotificationRuleStore.Type.ACCOUNT_EXCEPTIONS))
                || matchesKeyword(text, safeRules.list(NotificationRuleStore.Type.KEYWORD_EXCEPTIONS))) {
            return false;
        }
        if (matchesAccount(normalizedTitle, safeRules.list(NotificationRuleStore.Type.ACCOUNT_BLOCKLIST))
                || matchesKeyword(text, safeRules.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST))) {
            return true;
        }
        if (text.isEmpty()) {
            return false;
        }
        if (hasReviewedServicePromoPattern(normalizedTitle, text)) {
            return true;
        }
        if (hasKnownConversationChannel(channelId)) {
            return false;
        }
        if (looksLikeChatNotification(text)) {
            return false;
        }
        if (hasKnownPromoChannel(channelId) && hasSocialStoryPattern(text)) {
            return true;
        }
        return hasStrongPromoPattern(text);
    }

    public static String notificationSummary(Notification notification) {
        String summary = notificationText(notification).replace('\n', ' ').trim();
        if (summary.length() > 160) {
            return summary.substring(0, 160);
        }
        return summary;
    }

    public static String notificationMetadata(Notification notification) {
        if (notification == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendMetadata(builder, "channel", channelId(notification));
        appendMetadata(builder, "category", notification.category);
        appendMetadata(builder, "shortcut", hasShortcut(notification) ? "true" : "false");
        Bundle extras = notification.extras;
        if (extras != null) {
            String template = extras.getString(Notification.EXTRA_TEMPLATE);
            if (template != null) {
                int lastDot = template.lastIndexOf('.');
                appendMetadata(builder, "template", lastDot >= 0 ? template.substring(lastDot + 1) : template);
            }
        }
        return builder.toString();
    }

    static String channelId(Notification notification) {
        if (notification == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return "";
        }
        return notification.getChannelId();
    }

    private static boolean hasShortcut(Notification notification) {
        return notification != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && notification.getShortcutId() != null;
    }

    private static boolean looksLikeChatNotification(String text) {
        return containsAny(text,
                "tin nhan moi",
                "new message",
                "sent you",
                "da gui",
                "vua gui",
                "missed call",
                "cuoc goi nho",
                "mentioned you",
                "nhac den ban");
    }

    private static boolean hasReviewedServicePromoPattern(String title, String text) {
        if ("nap dien thoai".equals(title)) {
            return text.contains("giam ngay") && text.contains("lan nap dau tien");
        }
        if ("zcloud".equals(title)) {
            return text.contains("dung de vuot mat") && text.contains("lien lac");
        }
        return "zalo".equals(title) && (text.contains("tin moi tu zalo")
                || text.contains("nang cao an toan voi zalo"));
    }

    private static boolean hasKnownPromoChannel(String channelId) {
        return channelId != null && channelId.startsWith("zalo_05_social_story_channel_");
    }

    private static boolean hasOperationalSafeChannel(String channelId) {
        if (channelId == null) {
            return false;
        }
        return channelId.startsWith("zalo_03_call_channel_")
                || channelId.startsWith("zalo_09_call_channel_")
                || channelId.startsWith("zalo_10_db_action_channel_");
    }

    private static boolean hasKnownConversationChannel(String channelId) {
        if (channelId == null) {
            return false;
        }
        return channelId.startsWith("zalo_010_chat_channel_")
                || channelId.startsWith("zalo_020_chat_group_channel_");
    }

    private static boolean matchesKeyword(String text, java.util.List<String> rules) {
        for (String rule : rules) {
            String normalizedRule = normalized(rule);
            if (!normalizedRule.isEmpty() && text.contains(normalizedRule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAccount(String title, java.util.List<String> rules) {
        if (title.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (title.equals(normalized(rule))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSocialStoryPattern(String text) {
        return containsAny(text, "liked", "thich", "video", "story", "nhat ky", "watch", "xem");
    }

    private static boolean hasStrongPromoPattern(String text) {
        return hasEnglishPromoPattern(text) || hasVietnamesePromoPattern(text);
    }

    private static boolean hasEnglishPromoPattern(String text) {
        return containsAny(text, "watch now", "watch it now", "see more", "check it out")
                && containsAny(text, "liked this video", "liked a video", "friends liked", "friend liked", "video");
    }

    private static boolean hasVietnamesePromoPattern(String text) {
        return containsAny(text, "xem ngay", "xem lien", "xem them")
                && containsAny(text, "thich video", "ban be", "ban cua ban", "video", "nhat ky");
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String notificationText(Notification notification) {
        if (notification == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        append(builder, notification.tickerText);
        Bundle extras = notification.extras;
        if (extras != null) {
            append(builder, extras.getCharSequence(Notification.EXTRA_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
            append(builder, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                for (CharSequence line : lines) {
                    append(builder, line);
                }
            }
        }
        return builder.toString();
    }

    private static String joinContent(String title, String content) {
        if (title == null || title.isEmpty()) {
            return content == null ? "" : content;
        }
        if (content == null || content.isEmpty()) {
            return title;
        }
        return title + ' ' + content;
    }

    private static String notificationTitle(Notification notification) {
        if (notification == null || notification.extras == null) {
            return "";
        }
        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        return title == null ? "" : title.toString();
    }

    private static void append(StringBuilder builder, CharSequence value) {
        if (value == null) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private static void appendMetadata(StringBuilder builder, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append(' ');
        }
        builder.append(key).append('=').append(value);
    }

    private static String normalized(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.US);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd');
    }
}
