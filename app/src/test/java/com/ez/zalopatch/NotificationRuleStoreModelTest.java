package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class NotificationRuleStoreModelTest {
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
