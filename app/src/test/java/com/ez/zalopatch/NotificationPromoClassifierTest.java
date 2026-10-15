package com.ez.zalopatch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class NotificationPromoClassifierTest {
    private static final String CHAT_CHANNEL = "zalo_010_chat_channel_account";
    private static final String GROUP_CHANNEL = "zalo_020_chat_group_channel_account";
    private static final String SILENT_CHANNEL = "zalo_062_silent_inapp_channel_account";
    private static final String SOCIAL_CHANNEL = "zalo_05_social_story_channel_account";

    @Test
    public void reviewedPhoneTopUpPromoOverridesSafeChatChannel() {
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Nạp Điện Thoại",
                "🎁 Giảm ngay đến 10% cho lần nạp đầu tiên"));
    }

    @Test
    public void reviewedZCloudPromoIsBlocked() {
        assertTrue(NotificationPromoClassifier.isPromo(SILENT_CHANNEL, "zCloud",
                "Đừng để vuột mất nhiều năm liên lạc trên Zalo"));
    }

    @Test
    public void reviewedGenericZaloPromoOverridesSafeChatChannel() {
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Zalo",
                "✨ Tin mới từ Zalo"));
    }

    @Test
    public void reviewedZaloSafetyPromoOverridesSafeChatChannel() {
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Zalo",
                "4+ Nâng cao an toàn với Zalo"));
    }

    @Test
    public void legitimateOaMessageOnChatChannelRemainsAllowed() {
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Kingfoodmart",
                "Thông báo điểm tích luỹ bằng số điện thoại. Cảm ơn bạn đã mua sắm."));
    }

    @Test
    public void serviceTitlesWithoutReviewedPromoCopyRemainAllowed() {
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Nạp Điện Thoại",
                "Nạp tiền thành công"));
        assertFalse(NotificationPromoClassifier.isPromo(SILENT_CHANNEL, "zCloud",
                "Sao lưu danh bạ đã hoàn tất"));
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Zalo",
                "Bạn có tin nhắn mới"));
    }

    @Test
    public void socialStoryPromoRemainsBlocked() {
        assertTrue(NotificationPromoClassifier.isPromo(SOCIAL_CHANNEL, "Zalo",
                "Your friends just liked this video. Watch now!"));
    }

    @Test
    public void customKeywordBlocklistOverridesConversationChannels() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("khuyến mãi đặc biệt"), null, null, null);
        for (String channel : Arrays.asList(CHAT_CHANNEL, GROUP_CHANNEL)) {
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "OA account",
                    "Khuyen mai dac biet hôm nay", rules));
        }
    }

    @Test
    public void customKeywordRulesIncludeNotificationTitle() {
        NotificationRuleStore.RuleSet block = new NotificationRuleStore.RuleSet(
                Collections.singletonList("tài khoản quảng cáo"), null, null, null);
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tai Khoan Quang Cao",
                "Hello", block));

        NotificationRuleStore.RuleSet exception = new NotificationRuleStore.RuleSet(
                Collections.singletonList("hello"),
                Collections.singletonList("tài khoản quảng cáo"),
                null,
                null);
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tai Khoan Quang Cao",
                "Hello", exception));
    }

    @Test
    public void keywordExceptionOverridesCustomAndDefaultBlocking() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("giảm ngay"),
                Collections.singletonList("giao dịch hợp lệ"),
                Collections.singletonList("Nạp Điện Thoại"),
                null);
        String content = "Giảm ngay cho lần nạp đầu tiên, giao dịch hợp lệ";
        for (String channel : Arrays.asList(CHAT_CHANNEL, GROUP_CHANNEL)) {
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content));
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content,
                    rules.with(NotificationRuleStore.Type.KEYWORD_EXCEPTIONS, null)));
            assertFalse(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content, rules));
        }
    }

    @Test
    public void accountExceptionOverridesCustomAndDefaultBlocking() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("giảm ngay"), null,
                Collections.singletonList("Nạp Điện Thoại"),
                Collections.singletonList("NAP DIEN THOAI"));
        String content = "Giảm ngay cho lần nạp đầu tiên";
        for (String channel : Arrays.asList(CHAT_CHANNEL, GROUP_CHANNEL)) {
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content));
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content,
                    rules.with(NotificationRuleStore.Type.ACCOUNT_EXCEPTIONS, null)));
            assertFalse(channel, NotificationPromoClassifier.isPromo(channel, "Nạp Điện Thoại", content, rules));
        }
    }

    @Test
    public void accountRulesUseExactAccentInsensitiveTitle() {
        NotificationRuleStore.RuleSet block = new NotificationRuleStore.RuleSet(
                null, null, Collections.singletonList("Tài Khoản Quảng Cáo"), null);
        for (String channel : Arrays.asList(CHAT_CHANNEL, GROUP_CHANNEL)) {
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "Tai Khoan Quang Cao",
                    "Hello", block));
            assertTrue(channel, NotificationPromoClassifier.isPromo(channel, "TA\u0300I KHOA\u0309N QUA\u0309NG CA\u0301O",
                    "Hello", block));
            assertFalse(channel, NotificationPromoClassifier.isPromo(channel, "Tài Khoản Quảng Cáo 2",
                    "Hello", block));
            assertFalse(channel, NotificationPromoClassifier.isPromo(channel, "Other account",
                    "Tài Khoản Quảng Cáo", block));
        }

        NotificationRuleStore.RuleSet exception = new NotificationRuleStore.RuleSet(
                null, null,
                Collections.singletonList("Tài Khoản Quảng Cáo"),
                Arrays.asList("Tài Khoản Quảng Cáo"));
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tài Khoản Quảng Cáo",
                "Hello", exception));
    }

    @Test
    public void accountBlocklistDoesNotRequireNotificationText() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                null, null, Collections.singletonList("Tài Khoản Quảng Cáo"), null);
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tai Khoan Quang Cao",
                "", rules));
    }

    @Test
    public void operationalChannelsIgnoreCustomAndDefaultBlockRules() {
        for (String channel : Arrays.asList("zalo_03_call_channel_account",
                "zalo_09_call_channel_account", "zalo_10_db_action_channel_account")) {
            assertFalse(channel, NotificationPromoClassifier.isPromo(channel, "Zalo",
                    "Nâng cao an toàn với Zalo"));
            for (NotificationRuleStore.Type type : Arrays.asList(
                    NotificationRuleStore.Type.KEYWORD_BLOCKLIST,
                    NotificationRuleStore.Type.ACCOUNT_BLOCKLIST)) {
                NotificationRuleStore.RuleSet rules = NotificationRuleStore.RuleSet.empty()
                        .with(type, Collections.singletonList("ZALO"));
                assertFalse(channel + " " + type, NotificationPromoClassifier.isPromo(channel, "Zalo",
                        "Nâng cao an toàn với Zalo", rules));
            }
        }
    }

    @Test
    public void combiningMarkOnlyKeywordsNeitherBlockNorExemptNotifications() {
        NotificationRuleStore.RuleSet block = new NotificationRuleStore.RuleSet(
                Collections.singletonList("\u0301"), null, null, null);
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Contact", "Hello", block));

        NotificationRuleStore.RuleSet exception = new NotificationRuleStore.RuleSet(
                null, Collections.singletonList("\u0301"), null, null);
        assertTrue(NotificationPromoClassifier.isPromo(SOCIAL_CHANNEL, "Zalo",
                "Your friends just liked this video. Watch now!", exception));
    }
}
