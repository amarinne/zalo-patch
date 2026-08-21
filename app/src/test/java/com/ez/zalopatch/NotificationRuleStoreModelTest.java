package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NotificationRuleStoreModelTest {
    @Test
    public void propertyRulesWinWhenMirrorIsPresent() throws Exception {
        NotificationRuleStore.RuleSet property = new NotificationRuleStore.RuleSet(
                java.util.Collections.singletonList("property"), null, null, null);
        NotificationRuleStore.RuleSet provider = new NotificationRuleStore.RuleSet(
                java.util.Collections.singletonList("provider"), null, null, null);

        NotificationRuleStore.RuleSet resolved = NotificationRuleStore.resolve(
                NotificationRuleStore.encode(property), NotificationRuleStore.encode(provider));

        assertEquals(java.util.Collections.singletonList("property"),
                resolved.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST));
    }

    @Test
    public void providerRulesFillAnAbsentMirror() throws Exception {
        NotificationRuleStore.RuleSet provider = new NotificationRuleStore.RuleSet(
                null, java.util.Collections.singletonList("allowed"), null, null);

        NotificationRuleStore.RuleSet resolved = NotificationRuleStore.resolve(
                null, NotificationRuleStore.encode(provider));

        assertEquals(java.util.Collections.singletonList("allowed"),
                resolved.list(NotificationRuleStore.Type.KEYWORD_EXCEPTIONS));
    }

    @Test
    public void malformedPresentMirrorDoesNotFallThroughToProvider() throws Exception {
        NotificationRuleStore.RuleSet provider = new NotificationRuleStore.RuleSet(
                java.util.Collections.singletonList("provider"), null, null, null);

        NotificationRuleStore.RuleSet resolved = NotificationRuleStore.resolve(
                "not-json", NotificationRuleStore.encode(provider));

        assertEquals(0, resolved.total());
    }
    @Test
    public void rulesAreTrimmedAndDeduplicated() {
        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                Arrays.asList("  Promotion  ", "promotion", "Khuyến mãi", "khuyen mai", "Sale"),
                null, null, null);

        assertEquals(Arrays.asList("Promotion", "Khuyến mãi", "Sale"),
                rules.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST));
    }

    @Test
    public void ruleListsAndItemsAreNotArtificiallyBounded() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            values.add("rule-" + i);
        }
        values.set(0, repeat('x', 100));

        NotificationRuleStore.RuleSet rules = new NotificationRuleStore.RuleSet(
                values, null, null, null);

        assertEquals(20, rules.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST).size());
        assertEquals(100, rules.list(NotificationRuleStore.Type.KEYWORD_BLOCKLIST).get(0).length());
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
