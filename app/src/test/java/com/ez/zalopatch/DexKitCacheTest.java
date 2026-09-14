package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the DexKit pilot cache binding, codec, and invalidation rules. */
public final class DexKitCacheTest {
    private static final String DIGEST =
            "5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed";
    private static final long VERSION = 260802903L;
    private static final int MODULE = 203;

    @Test
    public void matchingEntryBinds() {
        DexKitCache.Entry entry = positive();
        assertTrue(binds(entry));
        assertTrue(DexKitCache.fullyResolved(entry));
    }

    @Test
    public void negativeEntryBindsButNeverResolves() {
        DexKitCache.Entry entry = new DexKitCache.Entry(VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE,
                "", "", true, "no_match", 0, 0, 41L, 9L, 0);
        assertTrue(binds(entry));
        assertFalse(DexKitCache.fullyResolved(entry));
    }

    @Test
    public void anyBindingChangeInvalidatesPositiveAndNegative() {
        DexKitCache.Entry positive = positive();
        DexKitCache.Entry negative = new DexKitCache.Entry(VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE,
                "", "", true, "ambiguous_match", 2, 2, 41L, 9L, 0);
        assertFalse(DexKitCache.bindsTo(negative, VERSION + 1, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION + 1, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, otherDigest(), 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, DIGEST, 8L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, DIGEST, 7L, 68157441L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION + 1, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT + 1, MODULE));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE + 1));
        assertFalse(DexKitCache.bindsTo(positive, VERSION, "", 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitCache.bindsTo(null, VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
    }

    @Test
    public void codecRoundTripsByteIdentically() throws Exception {
        DexKitCache.Entry entry = positive();
        String first = DexKitCache.serialize(entry);
        DexKitCache.Entry parsed = DexKitCache.parse(first);
        String second = DexKitCache.serialize(parsed);
        assertEquals(first, second);
        assertEquals(entry.adBind, parsed.adBind);
        assertEquals(entry.feedBind, parsed.feedBind);
        assertEquals(entry.codeDigest, parsed.codeDigest);
        assertEquals(entry.lastUpdateTime, parsed.lastUpdateTime);
        assertEquals(entry.apkSize, parsed.apkSize);
        assertEquals(entry.scanDurationMs, parsed.scanDurationMs);
    }

    @Test
    public void codecRejectsUnknownKeysOversizeAndBadBindings() {
        try {
            DexKitCache.parse("{\"version_code\":1}");
            assertTrue("missing binding must fail", false);
        } catch (Exception expected) {
            assertTrue(expected instanceof org.json.JSONException);
        }
        try {
            DexKitCache.parse(DexKitCache.serialize(positive()).replace(
                    "\"ad_bind\"", "\"evil_key\",\"ad_bind\""));
            assertTrue("unknown key must fail", false);
        } catch (Exception expected) {
            assertTrue(expected instanceof org.json.JSONException);
        }
        try {
            DexKitCache.parse("x".repeat(DexKitCache.MAX_ENTRY_BYTES + 1));
            assertTrue("oversize must fail", false);
        } catch (Exception expected) {
            assertTrue(expected instanceof org.json.JSONException);
        }
    }

    @Test
    public void negativeEntryMustNotCarryDescriptors() {
        DexKitCache.Entry broken = new DexKitCache.Entry(VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE,
                "c", "", true, "no_match", 1, 0, 41L, 9L, 0);
        try {
            DexKitCache.parse(DexKitCache.serialize(broken));
            assertTrue("negative entry with descriptors must fail", false);
        } catch (Exception expected) {
            assertTrue(expected instanceof org.json.JSONException);
        }
    }

    private DexKitCache.Entry positive() {
        return new DexKitCache.Entry(VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE,
                "c", "c", false, "", 1, 1, 812L, 1726051200000L, 0);
    }

    private boolean binds(DexKitCache.Entry entry) {
        return DexKitCache.bindsTo(entry, VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE);
    }

    @Test
    public void codecCarriesPartialAttempts() throws Exception {
        DexKitCache.Entry entry = new DexKitCache.Entry(VERSION, DIGEST, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE,
                "c", "", false, "ad only; feed ambiguous_margin", 1, 2, 41L, 9L, 2);
        DexKitCache.Entry parsed = DexKitCache.parse(DexKitCache.serialize(entry));
        assertEquals(2, parsed.partialAttempts);
        try {
            DexKitCache.parse(DexKitCache.serialize(entry).replace(
                    "\"partial_attempts\":2", "\"partial_attempts\":-1"));
            assertTrue("negative counter must fail", false);
        } catch (Exception expected) {
            assertTrue(expected instanceof org.json.JSONException);
        }
    }

    @Test
    public void hostGateAcceptsCurrentArtifactOnly() {
        assertTrue(DexKitStore.hostMatches(positive(), VERSION, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitStore.hostMatches(positive(), VERSION + 1, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitStore.hostMatches(positive(), VERSION, 8L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitStore.hostMatches(positive(), VERSION, 7L, 68157441L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitStore.hostMatches(positive(), VERSION, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION + 1, DexKitCache.RESOLVER_FORMAT, MODULE));
        assertFalse(DexKitStore.hostMatches(null, VERSION, 7L, 68157440L,
                DexKitZinstantFingerprint.QUERY_REVISION, DexKitCache.RESOLVER_FORMAT, MODULE));
    }

    private String otherDigest() {
        return "a55d59581d4e4d038a28bc17a3d12237f519566ecfb7140eb445a6fa045eef34";
    }
}
