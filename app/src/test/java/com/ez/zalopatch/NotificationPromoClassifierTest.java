package com.ez.zalopatch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class NotificationPromoClassifierTest {
    private static final String CHAT_CHANNEL = "zalo_010_chat_channel_account";
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
    public void customKeywordBlocklistOverridesChatChannel() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("khuyến mãi đặc biệt"), null, null, null);
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "OA account",
                "Khuyen mai dac biet hôm nay", rules));
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
                null,
                null);
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Nạp Điện Thoại",
                "Giảm ngay cho giao dịch hợp lệ", rules));
    }

    @Test
    public void accountRulesUseExactAccentInsensitiveTitle() {
        NotificationRuleStore.RuleSet block = new NotificationRuleStore.RuleSet(
                null, null, Collections.singletonList("Tài Khoản Quảng Cáo"), null);
        assertTrue(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tai Khoan Quang Cao",
                "Hello", block));
        assertFalse(NotificationPromoClassifier.isPromo(CHAT_CHANNEL, "Tài Khoản Quảng Cáo 2",
                "Hello", block));

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
    public void operationalChannelsIgnoreCustomBlockRules() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Collections.singletonList("incoming voice call"), null, null, null);
        assertFalse(NotificationPromoClassifier.isPromo("zalo_03_call_channel_account", "Contact",
                "Incoming voice call", rules));
    }
}
