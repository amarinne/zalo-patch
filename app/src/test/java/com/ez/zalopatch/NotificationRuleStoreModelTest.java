package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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

    @Test
    public void sanitizationPreservesFirstSpellingOrderAndDistinctHashCollisions() {
        assertEquals(Arrays.asList("ĐẶC BIỆT", "an", "c0", "\u0301", "Sale"),
                NotificationRuleStore.sanitize(Arrays.asList(null, " ", " ĐẶC BIỆT ",
                        "dac biet", "an", "c0", "\u0301", "\u0300", "Sale", "sale")));
    }

    @Test
    public void ruleSetsOwnTheirListsAndWithDoesNotChangeTheOriginal() {
        List<String> input = new ArrayList<>(Arrays.asList("First", "second"));
        NotificationRuleStore.RuleSet original = new NotificationRuleStore.RuleSet(
                input, input, input, input);
        input.clear();
        for (NotificationRuleStore.Type type : NotificationRuleStore.Type.values()) {
            assertEquals(Arrays.asList("First", "second"), original.list(type));
            assertThrows(UnsupportedOperationException.class, () -> original.list(type).add("third"));
            NotificationRuleStore.RuleSet changed = original.with(type, Arrays.asList(" New ", "new"));
            for (NotificationRuleStore.Type other : NotificationRuleStore.Type.values()) {
                assertEquals(other == type ? Arrays.asList("New") : Arrays.asList("First", "second"),
                        changed.list(other));
                assertEquals(Arrays.asList("First", "second"), original.list(other));
            }
        }
    }

    @Test
    public void decodeSanitizesEveryListAndRoundTripsWithoutChangingDisplayText() throws Exception {
        org.json.JSONObject json = new org.json.JSONObject().put("format_version", 1);
        for (NotificationRuleStore.Type type : NotificationRuleStore.Type.values()) {
            json.put(type.jsonKey, new org.json.JSONArray(Arrays.asList(
                    "  Khuyến mãi ", "KHUYEN MAI", "", "Đặc biệt", "Dac bie\u0323t")));
        }
        NotificationRuleStore.RuleSet decoded = NotificationRuleStore.decode(json.toString());
        NotificationRuleStore.RuleSet roundTrip = NotificationRuleStore.decode(NotificationRuleStore.encode(decoded));
        for (NotificationRuleStore.Type type : NotificationRuleStore.Type.values()) {
            assertEquals(Arrays.asList("Khuyến mãi", "Đặc biệt"), decoded.list(type));
            assertEquals(decoded.list(type), roundTrip.list(type));
        }
    }

    @Test
    public void decodeRejectsNonStringItemsInEveryList() throws Exception {
        for (NotificationRuleStore.Type type : NotificationRuleStore.Type.values()) {
            for (Object invalid : Arrays.asList(42, true, org.json.JSONObject.NULL)) {
                String json = new org.json.JSONObject().put("format_version", 1)
                        .put(type.jsonKey, new org.json.JSONArray().put("valid").put(invalid)).toString();
                assertThrows(IllegalArgumentException.class, () -> NotificationRuleStore.decode(json));
            }
        }
    }

    @Test
    public void largeMixedListPreservesUniqueOrderAndFirstSpelling() {
        List<String> input = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            String first = "Khuyến mãi " + i;
            expected.add(first);
            input.add("  " + first + "  ");
            input.add("KHUYEN MAI " + i);
        }
        assertEquals(expected, NotificationRuleStore.sanitize(input));
    }

    private static String repeat(char value, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
