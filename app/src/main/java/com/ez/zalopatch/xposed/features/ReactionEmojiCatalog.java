package com.ez.zalopatch.xposed.features;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The host's own emoji catalogue ({@code assets/emoji_by_cate.json}) restricted to native
 * Unicode emoji.
 *
 * <p>Host shape: a JSON array of categories, each {@code {"name":{"en":..,"vi":..},
 * "emojis":["...", ...]}}. Everything here is pure Java so the parsing, the bounds and
 * the emoji filter stay JVM-testable; the dialog only calls {@link #read} and renders the
 * result.
 *
 * <p>Fail-closed contract: a missing, oversized, malformed or empty catalogue yields an
 * empty list, and an entry that is not a plain Unicode emoji sequence is dropped. Zalo
 * emoticon codes, sticker ids, paid-reaction tokens and any other free text therefore can
 * never reach the selection callback.
 */
final class ReactionEmojiCatalog {
    /** Host asset name. Zalo ships this file in its own APK. */
    static final String ASSET_NAME = "emoji_by_cate.json";

    /** Hard read bound: the shipped file is ~26 KiB. */
    static final int MAX_CATALOG_BYTES = 1 << 20;
    static final int MAX_CATEGORIES = 16;
    static final int MAX_EMOJI_PER_CATEGORY = 512;
    static final int MAX_EMOJI_TOTAL = 2048;
    /** Longest accepted emoji sequence (ZWJ family / tag flag are the longest in the file). */
    static final int MAX_EMOJI_LENGTH = 16;

    static final class Category {
        final String title;
        final List<String> emojis;

        Category(String title, List<String> emojis) {
            this.title = title;
            this.emojis = emojis;
        }
    }

    private ReactionEmojiCatalog() {
    }

    /** Reads and parses the catalogue, or returns an empty list when it is unusable. */
    static List<Category> read(InputStream stream, String language) {
        return read(stream, language, null);
    }

    static List<Category> read(InputStream stream, String language,
                               BiConsumer<String, Throwable> failure) {
        String json = readBounded(stream, failure);
        if (json == null) {
            return Collections.emptyList();
        }
        return parse(json, language, failure);
    }

    /** Bounded UTF-8 read; null when the stream is missing, empty or larger than the bound. */
    static String readBounded(InputStream stream) {
        return readBounded(stream, null);
    }

    private static String readBounded(InputStream stream, BiConsumer<String, Throwable> failure) {
        if (stream == null) {
            report(failure, "catalog_stream_missing", null);
            return null;
        }
        try (InputStream input = stream;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                if (out.size() + read > MAX_CATALOG_BYTES) {
                    report(failure, "catalog_too_large", null);
                    return null;
                }
                out.write(buffer, 0, read);
            }
            if (out.size() == 0) {
                report(failure, "catalog_read_empty", null);
                return null;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Throwable throwable) {
            report(failure, "catalog_read", throwable);
            return null;
        }
    }

    /** Parses the catalogue text. Returns an empty list for anything malformed. */
    static List<Category> parse(String json, String language) {
        return parse(json, language, null);
    }

    private static List<Category> parse(String json, String language,
                                        BiConsumer<String, Throwable> failure) {
        if (json == null || json.isEmpty()) {
            report(failure, "catalog_parse_empty", null);
            return Collections.emptyList();
        }
        List<Category> categories = new ArrayList<>();
        int total = 0;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && categories.size() < MAX_CATEGORIES; i++) {
                JSONObject category = array.optJSONObject(i);
                if (category == null) {
                    continue;
                }
                JSONArray source = category.optJSONArray("emojis");
                if (source == null) {
                    continue;
                }
                List<String> emojis = new ArrayList<>();
                for (int j = 0; j < source.length()
                        && emojis.size() < MAX_EMOJI_PER_CATEGORY
                        && total + emojis.size() < MAX_EMOJI_TOTAL; j++) {
                    String emoji = source.optString(j, "");
                    if (isNativeEmoji(emoji) && !emojis.contains(emoji)) {
                        emojis.add(emoji);
                    }
                }
                if (emojis.isEmpty()) {
                    continue;
                }
                total += emojis.size();
                categories.add(new Category(title(category.optJSONObject("name"), language), emojis));
            }
        } catch (Throwable throwable) {
            report(failure, "catalog_parse", throwable);
            return Collections.emptyList();
        }
        if (categories.isEmpty()) report(failure, "catalog_empty", null);
        return categories;
    }

    private static void report(BiConsumer<String, Throwable> failure, String stage, Throwable cause) {
        if (failure != null) failure.accept(stage, cause);
    }

    /** Category title for the requested language, falling back to English, then to empty. */
    static String title(JSONObject name, String language) {
        if (name == null) {
            return "";
        }
        String localized = name.optString(language, "");
        if (!localized.isEmpty()) {
            return localized;
        }
        return name.optString("en", "");
    }

    /** "vi" only for Vietnamese; every other device language uses the English titles. */
    static String language(java.util.Locale locale) {
        return locale != null && "vi".equals(locale.getLanguage()) ? "vi" : "en";
    }

    /**
     * True only for a plain native Unicode emoji sequence.
     *
     * <p>Accepted: emoji and pictograph blocks, regional indicators, ZWJ sequences with a
     * base on both sides, variation selectors and skin tones after a base, whole keycap
     * sequences (one of {@code # * 0-9}, optional {@code U+FE0F}, then {@code U+20E3}), tag
     * sequences (subdivision flags), and the legacy text-default emoji Zalo ships
     * ({@code ©️ ®️ ™️ ℹ️}).
     *
     * <p>Rejected: any other use of ASCII, so {@code 1😀}, {@code (y)}, sticker ids and paid
     * reaction tokens never pass; plus a stray modifier, tag, variation selector or ZWJ with
     * no base to attach to.
     */
    static boolean isNativeEmoji(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_EMOJI_LENGTH) {
            return false;
        }
        int length = value.length();
        int first = value.codePointAt(0);
        // Keycaps are the only accepted ASCII: base, optional variation selector, U+20E3.
        if (isKeycapBase(first)) {
            int index = Character.charCount(first);
            if (index < length && value.codePointAt(index) == 0xFE0F) {
                index += 1;
            }
            return index < length
                    && Character.codePointAt(value, index) == 0x20E3
                    && index + Character.charCount(0x20E3) == length;
        }
        boolean base = false;
        boolean sawTag = false;
        boolean tagTerminated = false;
        boolean joinPending = false;
        int index = 0;
        while (index < length) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (isKeycapBase(codePoint) || codePoint == 0x20E3) {
                return false;
            }
            if (codePoint == 0xFE0F || codePoint == 0xFE0E) {
                if (!base || joinPending) {
                    return false;
                }
                continue;
            }
            if (codePoint == 0x200D) {
                if (!base || joinPending) {
                    return false;
                }
                joinPending = true;
                continue;
            }
            if (codePoint >= 0xE0020 && codePoint <= 0xE007F) {
                if (!base || joinPending) {
                    return false;
                }
                sawTag = true;
                tagTerminated = codePoint == 0xE007F;
                continue;
            }
            if (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF) {
                // Skin tone: a modifier, never a base of its own.
                if (!base || joinPending) {
                    return false;
                }
                continue;
            }
            if (isEmojiCodePoint(codePoint)) {
                base = true;
                joinPending = false;
                continue;
            }
            return false;
        }
        return base && !joinPending && (!sawTag || tagTerminated);
    }

    private static boolean isKeycapBase(int codePoint) {
        return codePoint == 0x23 || codePoint == 0x2A
                || (codePoint >= 0x30 && codePoint <= 0x39);
    }

    private static boolean isEmojiCodePoint(int codePoint) {
        if (codePoint == 0xA9 || codePoint == 0xAE
                || codePoint == 0x203C || codePoint == 0x2049
                || codePoint == 0x2122 || codePoint == 0x2139
                || codePoint == 0x3030 || codePoint == 0x303D
                || codePoint == 0x3297 || codePoint == 0x3299) {
            return true;
        }
        if (codePoint >= 0x1F000 && codePoint <= 0x1FAFF) {
            return true;
        }
        if (codePoint >= 0xE0020 && codePoint <= 0xE007F) {
            return true;
        }
        if (codePoint >= 0x2190 && codePoint <= 0x21FF) {
            return true;
        }
        if (codePoint >= 0x231A && codePoint <= 0x231B
                || codePoint == 0x2328 || codePoint == 0x23CF
                || (codePoint >= 0x23E9 && codePoint <= 0x23F3)
                || (codePoint >= 0x23F8 && codePoint <= 0x23FA)
                || codePoint == 0x24C2
                || (codePoint >= 0x25AA && codePoint <= 0x25AB)
                || codePoint == 0x25B6 || codePoint == 0x25C0
                || (codePoint >= 0x25FB && codePoint <= 0x25FE)) {
            return true;
        }
        if (codePoint >= 0x2600 && codePoint <= 0x27BF) {
            return true;
        }
        if (codePoint >= 0x2934 && codePoint <= 0x2935) {
            return true;
        }
        if (codePoint >= 0x2B05 && codePoint <= 0x2B07
                || codePoint == 0x2B1B || codePoint == 0x2B1C
                || codePoint == 0x2B50 || codePoint == 0x2B55) {
            return true;
        }
        // Letters, digits outside a keycap, punctuation and any other free text stay rejected.
        return false;
    }
}
