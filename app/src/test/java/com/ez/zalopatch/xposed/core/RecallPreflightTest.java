package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitOverlay;
import com.ez.zalopatch.DexKitRecallFingerprint;
import com.ez.zalopatch.DexKitVideoAdsFingerprint;
import com.ez.zalopatch.SymbolSchema;
import com.zing.zalo.data.entity.chat.message.MessageId;
import com.zing.zalo.db.zadb.wrapper.ZdbApiCode;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecallPreflightTest {
    public static final class Message {
        public MessageId identity;
        public String text;
        public String sender;
        public int type;
        public static String staticText;
    }

    public static final class Undo {
    }

    public static final class Record {
    }

    public static final class Writer {
        public String account;

        public void write(Message message, boolean selector, Undo undo) {
        }

        public ZdbApiCode update(Record record, MessageId id, boolean force) {
            return null;
        }

        public static void staticWrite(Message message, boolean selector, Undo undo) {
        }
    }

    public static final class Coordinator {
        public void consume(Object event) {
        }

        public void undo(Message message) {
        }

        public void preview(MessageId id, boolean selector, Undo undo, boolean state) {
        }
    }

    private Map<String, String> route() {
        Map<String, String> map = new LinkedHashMap<>();
        String prefix = DexKitRecallFingerprint.PREFIX;
        map.put(prefix + "model_class", Message.class.getName());
        map.put(prefix + "undo_class", Undo.class.getName());
        map.put(prefix + "record_class", Record.class.getName());
        map.put(prefix + "writer_class", Writer.class.getName());
        map.put(prefix + "coordinator_class", Coordinator.class.getName());
        map.put(prefix + "undo_handler_class", Coordinator.class.getName());
        map.put(prefix + "writer_method", "write");
        map.put(prefix + "update_method", "update");
        map.put(prefix + "coordinator_method", "consume");
        map.put(prefix + "preview_method", "preview");
        map.put(prefix + "undo_handler_method", "undo");
        map.put(prefix + "identity_json_method", "serialize");
        for (String name : new String[]{"identity", "text", "type", "sender", "owner"}) {
            map.put(prefix + name + "_field", name);
        }
        return map;
    }

    private SymbolSchema.Active profile(Map<String, String> route) {
        JSONObject composed = DexKitOverlay.compose(null, null, route, "", "");
        return SymbolSchema.dexkitOverlayForHooks(DexKitOverlay.buildProfile(99,
                "a".repeat(64), "b".repeat(64), composed, "static-verified"), 99);
    }

    private boolean preflight(Map<String, String> route) {
        return SymbolPreflight.checkRecall(profile(route), getClass().getClassLoader(),
                new ArrayList<>());
    }

    @Test
    public void composedRouteArmsIndependentFamily() {
        assertTrue(preflight(route()));
        SymbolPreflight.Result checked = SymbolPreflight.inspect(profile(route()),
                getClass().getClassLoader());
        assertTrue(checked.recallErrors.toString(), checked.recall);
        assertFalse(checked.statusPrivacy);
        assertTrue(DexKitFamilyResolver.familyStates(profile(route()),
                getClass().getClassLoader(), route(), null).recall);
        Map<String, String> incomplete = route();
        incomplete.remove(DexKitRecallFingerprint.PREFIX + "writer_method");
        SymbolPreflight.Result rejected = SymbolPreflight.inspect(profile(incomplete),
                getClass().getClassLoader());
        assertFalse(rejected.recall);
        assertEquals(checked.chatReaction, rejected.chatReaction);
    }

    @Test
    public void everyRequiredAnchorAndCorrectFieldTypeAreMandatory() {
        for (String path : DexKitRecallFingerprint.paths()) {
            Map<String, String> missing = route();
            missing.remove(path);
            assertFalse(path, preflight(missing));
        }
        Map<String, String> wrong = route();
        wrong.put("symbols.recall.owner_field", "wrongOwner");
        assertFalse(preflight(wrong));
        wrong = route();
        wrong.put("symbols.recall.text_field", "staticText");
        assertFalse(preflight(wrong));
        wrong = route();
        wrong.put("symbols.recall.writer_method", "staticWrite");
        assertFalse(preflight(wrong));
        wrong = route();
        wrong.put("symbols.recall.undo_handler_method", "consume");
        assertFalse(preflight(wrong));
    }

    @Test
    public void otherwiseCompleteExactProfileStillNeedsMissingRecall() throws Exception {
        SymbolPreflight.Result checked = new SymbolPreflight.Result();
        for (Field field : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (field.getType() == boolean.class) {
                field.setBoolean(checked, true);
            }
        }
        for (DexKitVideoAdsFingerprint.Route route : DexKitVideoAdsFingerprint.Route.values()) {
            checked.videoAds.put(route, Boolean.TRUE);
        }
        SymbolSchema.Active exact = profile(route());
        assertTrue(DexKitFamilyResolver.completeExactCoverage(exact, checked));
        checked.recall = false;
        assertFalse(DexKitFamilyResolver.completeExactCoverage(exact, checked));
    }

    @Test
    public void exactPopulatedLeavesWinWhileEmptyLeavesUseDiscovery() throws Exception {
        JSONObject exact = new JSONObject().put("recall", new JSONObject()
                .put("writer_method", "staticWrite").put("text_field", ""));
        JSONObject symbols = DexKitOverlay.compose(exact, null, route(), "", "");
        assertEquals("staticWrite", symbols.getJSONObject("recall").getString("writer_method"));
        assertEquals("text", symbols.getJSONObject("recall").getString("text_field"));
        SymbolSchema.Active active = SymbolSchema.dexkitOverlayForHooks(DexKitOverlay.buildProfile(
                99, "a".repeat(64), "b".repeat(64), symbols, "static-verified"), 99);
        assertFalse(SymbolPreflight.checkRecall(active, getClass().getClassLoader(),
                new ArrayList<>()));
    }
}
