package com.ez.zalopatch.xposed.features;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Bounds, language fallback and the native-emoji filter of the host catalogue reader.
 * Every rejected case here is a way a Zalo emoticon code, sticker id or paid reaction
 * token could otherwise reach the picker callback.
 */
public final class ReactionEmojiCatalogTest {
    private static final String SMILE = "\uD83D\uDE00";
    private static final String THUMB_SKIN = "\uD83D\uDC4D\uD83C\uDFFB";
    private static final String COPYRIGHT = "\u00A9\uFE0F";
    private static final String KEYCAP_HASH = "#\uFE0F\u20E3";
    private static final String KEYCAP_ONE = "1\uFE0F\u20E3";
    private static final String HEART = "\u2764\uFE0F";
    private static final String FAMILY = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC66";
    private static final String ENGLAND_FLAG =
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F";

    private static final String CATALOG = "["
            + "{\"name\":{\"en\":\"Faces\",\"vi\":\"Khuôn mặt\"},\"emojis\":[\"" + SMILE + "\",\""
            + THUMB_SKIN + "\",\"(y)\",\"sticker:12345\"," + "\"1" + SMILE + "\",\""
            + COPYRIGHT + "\",\"" + KEYCAP_HASH + "\",\"" + KEYCAP_ONE + "\",\"" + HEART
            + "\",\"" + FAMILY + "\",\"" + ENGLAND_FLAG + "\"]},"
            + "{\"name\":{\"en\":\"Empty\"},\"emojis\":[\"\",\"sticker\"]},"
            + "{\"name\":{\"vi\":\"Không tên\"},\"emojis\":[\"" + SMILE + "\"]}"
            + "]";

    @Test public void keepsOnlyNativeEmojiAndDropsHostTokens() {
        List<ReactionEmojiCatalog.Category> categories =
                ReactionEmojiCatalog.parse(CATALOG, "en");
        // The middle category has no valid entry left after filtering and is dropped.
        assertEquals(2, categories.size());
        assertEquals("Faces", categories.get(0).title);
        assertEquals(List.of(SMILE, THUMB_SKIN, COPYRIGHT, KEYCAP_HASH, KEYCAP_ONE, HEART,
                FAMILY, ENGLAND_FLAG), categories.get(0).emojis);
        // An untranslated title stays empty rather than borrowing another language.
        assertEquals("", categories.get(1).title);
        assertEquals(List.of(SMILE), categories.get(1).emojis);
    }

    @Test public void languageSelectionFallsBackToEnglishThenToNothing() {
        assertEquals("vi", ReactionEmojiCatalog.language(Locale.forLanguageTag("vi-VN")));
        assertEquals("en", ReactionEmojiCatalog.language(Locale.US));
        assertEquals("en", ReactionEmojiCatalog.language(null));
        assertEquals("Khuôn mặt",
                ReactionEmojiCatalog.parse(CATALOG, "vi").get(0).title);
        assertEquals("Không tên",
                ReactionEmojiCatalog.parse(CATALOG, "vi").get(1).title);
        assertEquals("", ReactionEmojiCatalog.parse(
                "[{\"name\":{\"vi\":\"x\"},\"emojis\":[\"" + SMILE + "\"]}]", "en").get(0).title);
    }

    @Test public void malformedOrMissingCatalogueFailsClosed() {
        assertTrue(ReactionEmojiCatalog.parse(null, "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.parse("", "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.parse("{\"emoji\":1}", "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.parse("[]", "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.parse("[{\"name\":{}}]", "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.parse(
                "[{\"emojis\":[\"(y)\",\"sticker:1\"]}]", "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.read(null, "en").isEmpty());
        assertTrue(ReactionEmojiCatalog.read(
                new ByteArrayInputStream(new byte[0]), "en").isEmpty());
    }

    @Test public void diagnosticsDistinguishReadParseEmptyAndBoundFailures() {
        java.util.List<String> reasons = new java.util.ArrayList<>();
        java.util.function.BiConsumer<String, Throwable> capture = (stage, cause) ->
                reasons.add(stage + ":" + (cause == null ? "none" : cause.getClass().getName()));
        assertTrue(ReactionEmojiCatalog.read(null, "en", capture).isEmpty());
        assertEquals(List.of("catalog_stream_missing:none"), reasons);
        reasons.clear();
        assertTrue(ReactionEmojiCatalog.read(new ByteArrayInputStream(new byte[0]), "en", capture).isEmpty());
        assertEquals(List.of("catalog_read_empty:none"), reasons);
        reasons.clear();
        assertTrue(ReactionEmojiCatalog.read(new ByteArrayInputStream(
                "private invalid payload".getBytes(StandardCharsets.UTF_8)), "en", capture).isEmpty());
        assertEquals(List.of("catalog_parse:org.json.JSONException"), reasons);
        reasons.clear();
        assertTrue(ReactionEmojiCatalog.read(new ByteArrayInputStream(
                "[]".getBytes(StandardCharsets.UTF_8)), "en", capture).isEmpty());
        assertEquals(List.of("catalog_empty:none"), reasons);
        reasons.clear();
        assertTrue(ReactionEmojiCatalog.read(new ByteArrayInputStream(
                new byte[ReactionEmojiCatalog.MAX_CATALOG_BYTES + 1]), "en", capture).isEmpty());
        assertEquals(List.of("catalog_too_large:none"), reasons);
        reasons.clear();
        assertFalse(ReactionEmojiCatalog.read(new ByteArrayInputStream(
                CATALOG.getBytes(StandardCharsets.UTF_8)), "en", capture).isEmpty());
        assertTrue("a usable catalogue emits no failure", reasons.isEmpty());
    }

    @Test public void diagnosticReadRetainsExceptionTypeWithoutChangingFailClosedResult() {
        java.io.IOException failure = new java.io.IOException("private fixture message");
        java.util.List<Throwable> causes = new java.util.ArrayList<>();
        java.util.List<String> stages = new java.util.ArrayList<>();
        java.io.InputStream stream = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw failure; }
        };
        assertTrue(ReactionEmojiCatalog.read(stream, "en", (stage, cause) -> {
            stages.add(stage);
            causes.add(cause);
        }).isEmpty());
        assertEquals(List.of("catalog_read"), stages);
        assertEquals(List.of(failure), causes);
    }

    @Test public void readIsBoundedAndDecodesUtf8() {
        List<ReactionEmojiCatalog.Category> categories = ReactionEmojiCatalog.read(
                new ByteArrayInputStream(CATALOG.getBytes(StandardCharsets.UTF_8)), "en");
        assertEquals(2, categories.size());
        StringBuilder oversized = new StringBuilder("[{\"name\":{\"en\":\"x\"},\"emojis\":[");
        while (oversized.length() < ReactionEmojiCatalog.MAX_CATALOG_BYTES + 16) {
            oversized.append("\"x\",");
        }
        oversized.append("]}]");
        assertTrue(ReactionEmojiCatalog.read(new ByteArrayInputStream(
                oversized.toString().getBytes(StandardCharsets.UTF_8)), "en").isEmpty());
    }

    @Test public void catalogueBoundsCapCategoriesAndEmojiPerCategory() {
        StringBuilder wide = new StringBuilder("[{\"name\":{\"en\":\"Big\"},\"emojis\":[");
        int wanted = ReactionEmojiCatalog.MAX_EMOJI_PER_CATEGORY + 60;
        for (int i = 0; i < wanted; i++) {
            wide.append(i == 0 ? "" : ",").append('"')
                    .appendCodePoint(0x1F300 + i).append('"');
        }
        wide.append("]}]");
        assertEquals(ReactionEmojiCatalog.MAX_EMOJI_PER_CATEGORY,
                ReactionEmojiCatalog.parse(wide.toString(), "en").get(0).emojis.size());

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < ReactionEmojiCatalog.MAX_CATEGORIES + 4; i++) {
            if (i > 0) {
                many.append(',');
            }
            many.append("{\"name\":{\"en\":\"c").append(i).append("\"},\"emojis\":[\"")
                    .appendCodePoint(0x1F300 + i).append("\"]}");
        }
        many.append(']');
        assertEquals(ReactionEmojiCatalog.MAX_CATEGORIES,
                ReactionEmojiCatalog.parse(many.toString(), "en").size());
    }

    @Test public void acceptsRealEmojiShapes() {
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(SMILE));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(THUMB_SKIN));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(COPYRIGHT));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(HEART));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(FAMILY));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(ENGLAND_FLAG));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(KEYCAP_HASH));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji(KEYCAP_ONE));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji("0\uFE0F\u20E3"));
        assertTrue(ReactionEmojiCatalog.isNativeEmoji("*\u20E3"));
    }

    @Test public void rejectsAsciiMixedWithEmojiAndStrayComponents() {
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("1"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("#"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(SMILE + "1"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("1" + SMILE));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(SMILE + "(y)"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\uFE0F"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\u20E3"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(SMILE + "\u20E3"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\uD83C\uDFFB"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(SMILE + "\u200D"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\u200D" + SMILE));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\uDB40\uDC67\uDB40\uDC62\uDB40\uDC7F"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("(y)"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(":))"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("sticker:12345"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("emoji_123"));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji("hello " + SMILE));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(" "));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(""));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(null));
        assertFalse(ReactionEmojiCatalog.isNativeEmoji(
                SMILE + SMILE + SMILE + SMILE + SMILE + SMILE + SMILE + SMILE + SMILE));
    }
}
