package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the bottom-tabs state fingerprint (oh1/w-shaped fixture). */
public final class DexKitBottomTabsFingerprintTest {
    private static final String STATE = "oh1.w";
    private static final String ENUM = "oh1.u";

    @Test
    public void fullShapeResolvesEveryAnchor() {
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, stateMethods(), stateLayout());
        assertTrue(resolution.resolved());
        assertEquals(STATE, resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_STATE_CLASS));
        assertEquals(ENUM, resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_ENUM_CLASS));
        assertEquals("h", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_SINGLETON));
        assertEquals("g", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_ICON));
        assertEquals("p", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_REBUILD));
        assertEquals("q", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_REFRESH));
        assertEquals("a", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_HIDE_DISCOVERY));
        assertEquals("b", resolution.anchors.get(
                DexKitBottomTabsFingerprint.ANCHOR_GROUP_FLAG));
        assertEquals("k", resolution.anchors.get(
                "symbols.bottom_tabs.message_index_method"));
        assertEquals("m", resolution.anchors.get(
                "symbols.bottom_tabs.size_method"));
        assertEquals("b", resolution.anchors.get(
                "symbols.bottom_tabs.message_index_field"));
        assertEquals("j", resolution.anchors.get(
                "symbols.bottom_tabs.group_enabled_field"));
        assertEquals("p", resolution.anchors.get("symbols.bottom_tabs.icons_field"));
        assertEquals("o", resolution.anchors.get("symbols.bottom_tabs.preloaded_field"));
        assertEquals(2 + 14 + 15, resolution.anchors.size());
    }

    @Test
    public void selectStateClassRequiresFullShape() {
        Map<String, List<DexKitBottomTabsFingerprint.MethodHit>> dumps = new LinkedHashMap<>();
        dumps.put("other.State", stateMethods(STATE));
        assertEquals("", DexKitBottomTabsFingerprint.selectStateClass(dumps));
        dumps.put(STATE, stateMethods(STATE));
        assertEquals(STATE, DexKitBottomTabsFingerprint.selectStateClass(dumps));
        dumps.put("third.State", stateMethods("third.State"));
        assertEquals("", DexKitBottomTabsFingerprint.selectStateClass(dumps));
    }

    @Test
    public void layoutGateAcceptsRenamedFields() {
        DexKitBottomTabsFingerprint.FieldLayout renamed =
                new DexKitBottomTabsFingerprint.FieldLayout(
                        Arrays.asList("indexA", "indexB", "indexC", "indexD", "indexE",
                                "indexF", "indexG", "indexH"),
                        Arrays.asList("enabledA", "enabledB", "enabledC", "enabledD",
                                "enabledE"),
                        "iconArray", "preloadedArray", true);
        List<DexKitBottomTabsFingerprint.MethodHit> methods = new ArrayList<>();
        for (DexKitBottomTabsFingerprint.MethodHit method : stateMethods()) {
            List<String> fields = new ArrayList<>();
            for (String usedField : method.usedFields) {
                String name = usedField.substring(usedField.indexOf('#') + 1);
                int intIndex = stateLayout().intFields.indexOf(name);
                int boolIndex = stateLayout().boolFields.indexOf(name);
                fields.add(STATE + "#" + (intIndex >= 0 ? renamed.intFields.get(intIndex)
                        : boolIndex >= 0 ? renamed.boolFields.get(boolIndex) : name));
            }
            methods.add(new DexKitBottomTabsFingerprint.MethodHit(method.owner, method.name,
                    method.returnType, method.paramTypes, method.isStatic, fields,
                    method.invokedMethods));
        }
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, methods, renamed);
        assertTrue(resolution.status, resolution.resolved());
        assertEquals("indexA", resolution.anchors.get("symbols.bottom_tabs.message_index_field"));
        assertEquals("enabledA", resolution.anchors.get("symbols.bottom_tabs.group_enabled_field"));
        assertEquals("k", resolution.anchors.get("symbols.bottom_tabs.message_index_method"));
    }

    @Test
    public void layoutGateRejectsMissingFields() {
        DexKitBottomTabsFingerprint.FieldLayout missing =
                new DexKitBottomTabsFingerprint.FieldLayout(
                        Arrays.asList("b", "c", "d", "e", "f", "g", "h"),
                        Arrays.asList("j", "k", "l", "m", "n"), "p", "o", true);
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, stateMethods(), missing);
        assertFalse(resolution.resolved());
        assertEquals("field_shape_changed", resolution.status);
    }

    @Test
    public void voidPatternRejectsUnknownThirdMethod() {
        List<DexKitBottomTabsFingerprint.MethodHit> hits = stateMethods();
        List<DexKitBottomTabsFingerprint.MethodHit> extra = new ArrayList<>(hits);
        extra.add(new DexKitBottomTabsFingerprint.MethodHit(STATE, "r", "void",
                new ArrayList<String>(), false, new ArrayList<String>(),
                new ArrayList<String>()));
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, extra, stateLayout());
        assertFalse(resolution.resolved());
        assertEquals("void_pattern_changed", resolution.status);
    }

    @Test
    public void ambiguousSingletonStaysUnavailable() {
        List<DexKitBottomTabsFingerprint.MethodHit> hits = stateMethods();
        List<DexKitBottomTabsFingerprint.MethodHit> doubled = new ArrayList<>(hits);
        doubled.add(new DexKitBottomTabsFingerprint.MethodHit(STATE, "h2", STATE,
                new ArrayList<String>(), true, new ArrayList<String>(),
                new ArrayList<String>()));
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, doubled, stateLayout());
        assertFalse(resolution.resolved());
        assertEquals("ambiguous_singleton", resolution.status);
    }

    @Test
    public void constructorsNeverCountAsFamilyMethods() {
        List<DexKitBottomTabsFingerprint.MethodHit> hits = stateMethods();
        hits.add(new DexKitBottomTabsFingerprint.MethodHit(STATE, "<init>", "void",
                Arrays.asList("int", "java.lang.String"), false, new ArrayList<String>(),
                new ArrayList<String>()));
        hits.add(new DexKitBottomTabsFingerprint.MethodHit(STATE, "<init>", "void",
                new ArrayList<String>(), false, new ArrayList<String>(),
                new ArrayList<String>()));
        DexKitBottomTabsFingerprint.Resolution resolution =
                DexKitBottomTabsFingerprint.resolve(STATE, hits, stateLayout());
        assertTrue(resolution.resolved());
        Map<String, List<DexKitBottomTabsFingerprint.MethodHit>> dumps = new LinkedHashMap<>();
        dumps.put(STATE, hits);
        assertEquals(STATE, DexKitBottomTabsFingerprint.selectStateClass(dumps));
        assertEquals("8i/4b/3v/1s/1e",
                DexKitBottomTabsFingerprint.shapeSummary(dumps).get(STATE));
    }

    @Test
    public void calibrationConfirmsLetterRoles() {
        Map<String, String> roles = new HashMap<>();
        roles.put("message_index", "k");
        roles.put("phonebook_index", "l");
        roles.put("group_index", "f");
        roles.put("discovery_index", "e");
        roles.put("timeline_index", "n");
        roles.put("more_index", "j");
        roles.put("me_index", "i");
        roles.put("size", "m");
        Map<String, Integer> values = new HashMap<>();
        values.put("k", 0);
        values.put("l", 1);
        values.put("f", 2);
        values.put("e", 3);
        values.put("n", 4);
        values.put("j", 5);
        values.put("i", 6);
        values.put("m", 7);
        assertEquals("full", DexKitBottomTabsFingerprint.calibrate(values, roles));
    }

    @Test
    public void calibrationAbstainsOnUniformUninitializedState() {
        Map<String, String> roles = new HashMap<>();
        roles.put("message_index", "k");
        roles.put("phonebook_index", "l");
        roles.put("size", "m");
        Map<String, Integer> zeros = new HashMap<>();
        zeros.put("k", 0);
        zeros.put("l", 0);
        zeros.put("m", 0);
        assertEquals("inconclusive", DexKitBottomTabsFingerprint.calibrate(zeros, roles));
        Map<String, Integer> pairUniform = new HashMap<>();
        pairUniform.put("k", 0);
        pairUniform.put("l", 0);
        assertEquals("inconclusive",
                DexKitBottomTabsFingerprint.calibrate(pairUniform, roles));
    }
    @Test
    public void calibrationVetoesContradictionButAbstainsOutOfRange() {
        Map<String, String> roles = new HashMap<>();
        roles.put("message_index", "k");
        roles.put("phonebook_index", "l");
        roles.put("size", "m");
        Map<String, Integer> contradicted = new HashMap<>();
        contradicted.put("k", 3);
        contradicted.put("l", 1);
        assertEquals("contradicted",
                DexKitBottomTabsFingerprint.calibrate(contradicted, roles));
        Map<String, Integer> hidden = new HashMap<>();
        hidden.put("k", 0);
        hidden.put("l", -1);
        assertEquals("inconclusive",
                DexKitBottomTabsFingerprint.calibrate(hidden, roles));
        assertEquals("inconclusive",
                DexKitBottomTabsFingerprint.calibrate(new HashMap<String, Integer>(), roles));
    }

    @Test
    public void calibrationConfirmsFilteredPositionsOnDevice() {
        Map<String, String> roles = new HashMap<>();
        roles.put("message_index", "k");
        roles.put("phonebook_index", "l");
        roles.put("group_index", "f");
        roles.put("discovery_index", "e");
        roles.put("timeline_index", "n");
        roles.put("more_index", "j");
        roles.put("me_index", "i");
        roles.put("size", "m");
        Map<String, Integer> values = new HashMap<>();
        values.put("k", 0);
        values.put("l", 1);
        values.put("f", -1);
        values.put("e", 2);
        values.put("n", 3);
        values.put("j", -1);
        values.put("i", 4);
        values.put("m", 5);
        assertEquals("full", DexKitBottomTabsFingerprint.calibrate(values, roles));
    }

    @Test
    public void calibrationVetoesGapsAndSizeMismatch() {
        Map<String, String> roles = new HashMap<>();
        roles.put("message_index", "k");
        roles.put("phonebook_index", "l");
        roles.put("size", "m");
        Map<String, Integer> gap = new HashMap<>();
        gap.put("k", 0);
        gap.put("l", 2);
        assertEquals("contradicted", DexKitBottomTabsFingerprint.calibrate(gap, roles));
        Map<String, Integer> sizeMismatch = new HashMap<>();
        sizeMismatch.put("k", 0);
        sizeMismatch.put("l", 1);
        sizeMismatch.put("m", 9);
        assertEquals("contradicted",
                DexKitBottomTabsFingerprint.calibrate(sizeMismatch, roles));
    }

    private List<DexKitBottomTabsFingerprint.MethodHit> stateMethods() {
        return stateMethods(STATE);
    }

    private List<DexKitBottomTabsFingerprint.MethodHit> stateMethods(String owner) {
        List<DexKitBottomTabsFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(staticMethod(owner, "h", owner));
        hits.add(new DexKitBottomTabsFingerprint.MethodHit(owner, "g", "int",
                Arrays.asList(ENUM), true, new ArrayList<String>(), new ArrayList<String>()));
        hits.add(voidMethod(owner, "o"));
        hits.add(voidMethod(owner, "p"));
        hits.add(voidMethod(owner, "q", owner + "#p"));
        hits.add(getter(owner, "a", "boolean", owner + "#l"));
        hits.add(getter(owner, "b", "boolean", owner + "#j"));
        hits.add(getter(owner, "c", "boolean", owner + "#n"));
        hits.add(getter(owner, "d", "boolean", owner + "#m"));
        hits.add(getter(owner, "e", "int", owner + "#e"));
        hits.add(getter(owner, "f", "int", owner + "#d"));
        hits.add(getter(owner, "i", "int", owner + "#h"));
        hits.add(getter(owner, "j", "int", owner + "#g"));
        hits.add(getter(owner, "k", "int", owner + "#b"));
        hits.add(getter(owner, "l", "int", owner + "#c"));
        hits.add(getter(owner, "m", "int", owner + "#i"));
        hits.add(getter(owner, "n", "int", owner + "#f"));
        return hits;
    }

    private DexKitBottomTabsFingerprint.MethodHit staticMethod(String owner, String name,
                                                                 String returns) {
        return new DexKitBottomTabsFingerprint.MethodHit(owner, name, returns,
                new ArrayList<String>(), true, new ArrayList<String>(), new ArrayList<String>());
    }

    private DexKitBottomTabsFingerprint.MethodHit voidMethod(String owner, String name,
                                                             String... invokes) {
        return new DexKitBottomTabsFingerprint.MethodHit(owner, name, "void",
                new ArrayList<String>(), false, new ArrayList<String>(),
                new ArrayList<>(Arrays.asList(invokes)));
    }

    private DexKitBottomTabsFingerprint.MethodHit getter(String owner, String name,
                                                         String returns, String field) {
        return new DexKitBottomTabsFingerprint.MethodHit(owner, name, returns,
                new ArrayList<String>(), false, new ArrayList<>(Arrays.asList(field)),
                new ArrayList<String>());
    }

    private DexKitBottomTabsFingerprint.FieldLayout stateLayout() {
        return new DexKitBottomTabsFingerprint.FieldLayout(
                Arrays.asList("b", "c", "d", "e", "f", "g", "h", "i"),
                Arrays.asList("j", "k", "l", "m", "n", "q"), "p", "o", true);
    }
}
