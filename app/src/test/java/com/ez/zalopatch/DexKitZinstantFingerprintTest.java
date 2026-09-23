package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Offline verification for the Zinstant ad-bind pilot fingerprint.
 *
 * <p>Uses only the shared {@link DexKitZinstantFingerprint} definition with hand-built hits:
 * no native library, no APK, no Xposed. Runtime consumes the same definition through
 * {@code DexKitZinstantResolver}, so these predicates are the semantic proof, not a shadow.
 */
public final class DexKitZinstantFingerprintTest {
    private static final String HASH = "5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed";

    @Test
    public void recordedDescriptorsResolveForEveryRetainedVersion() {
        long[] retainedVersions = {260602901L, 260701901L, 260801903L, 260802903L};
        String[][] descriptors = {{"i", "s"}, {"c", "c"}, {"c", "c"}, {"c", "c"}};
        assertEquals(retainedVersions.length, descriptors.length);
        for (int index = 0; index < retainedVersions.length; index++) {
            assertTrue(DexKitZinstantFingerprint.isRetainedVersion(retainedVersions[index]));
            String[] descriptor = descriptors[index];
            FingerprintResolver.Resolution ad = evaluate(
                    DexKitZinstantFingerprint.ANCHOR_AD_BIND, descriptor[0],
                    DexKitZinstantFingerprint.AD_PARAM_COUNT);
            FingerprintResolver.Resolution feed = evaluate(
                    DexKitZinstantFingerprint.ANCHOR_FEED_BIND, descriptor[1],
                    DexKitZinstantFingerprint.FEED_PARAM_COUNT);
            assertEquals(descriptor[0], ad.symbol);
            assertEquals(descriptor[1], feed.symbol);
            assertTrue(ad.margin >= FingerprintResolver.MINIMUM_MARGIN);
            assertTrue(feed.margin >= FingerprintResolver.MINIMUM_MARGIN);
        }
        assertFalse(DexKitZinstantFingerprint.isRetainedVersion(260901903L));
    }

    @Test
    public void evaluationIsDeterministicUnderInputReordering() {
        List<DexKitZinstantFingerprint.MethodHit> hits = new ArrayList<>(Arrays.asList(
                hit(DexKitZinstantFingerprint.OWNER_AD_VIEW, "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT),
                hit("com.example.Unrelated", "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT)));
        FingerprintResolver.Resolution first = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, hits, HASH, HASH, false);
        List<DexKitZinstantFingerprint.MethodHit> reversed = new ArrayList<>(hits);
        Collections.reverse(reversed);
        FingerprintResolver.Resolution second = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, reversed, HASH, HASH, false);
        assertEquals("c", first.symbol);
        assertEquals(first.canonical(), second.canonical());
    }

    @Test
    public void zeroMatchesStayUnavailable() {
        FingerprintResolver.Resolution resolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND,
                Collections.emptyList(), HASH, HASH, false);
        assertEquals("no_candidates", resolution.status);
    }

    @Test
    public void duplicateMatchesStayAmbiguous() {
        List<DexKitZinstantFingerprint.MethodHit> hits = Arrays.asList(
                hit(DexKitZinstantFingerprint.OWNER_AD_VIEW, "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT),
                hit(DexKitZinstantFingerprint.OWNER_AD_VIEW, "d", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT));
        FingerprintResolver.Resolution resolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, hits, HASH, HASH, false);
        assertEquals("ambiguous_margin", resolution.status);
    }

    @Test
    public void sameSignatureWithUnrelatedOwnerIsNotSemanticProof() {
        List<DexKitZinstantFingerprint.MethodHit> hits = Collections.singletonList(
                hit("com.zing.zalo.unrelated.Widget", "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT));
        FingerprintResolver.Resolution resolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, hits, HASH, HASH, false);
        assertEquals("confidence_below_threshold", resolution.status);
    }

    @Test
    public void obfuscatedNameAloneIsNeverAccepted() {
        // Right name, wrong owner: the recorded bind name on an unrelated class proves nothing.
        List<DexKitZinstantFingerprint.MethodHit> hits = Collections.singletonList(
                hit("com.zing.zalo.unrelated.Widget", "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT));
        FingerprintResolver.Resolution resolution = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, hits, HASH, HASH, true);
        assertEquals("confidence_below_threshold", resolution.status);
    }

    @Test
    public void missingViewBoundaryOrConstructorFailsMandatoryPredicates() {
        List<DexKitZinstantFingerprint.MethodHit> noView = Collections.singletonList(
                new DexKitZinstantFingerprint.MethodHit(
                        DexKitZinstantFingerprint.OWNER_AD_VIEW, "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT, false, true));
        assertEquals("confidence_below_threshold", DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, noView, HASH, HASH, false).status);
        List<DexKitZinstantFingerprint.MethodHit> noConstructor = Collections.singletonList(
                new DexKitZinstantFingerprint.MethodHit(
                        DexKitZinstantFingerprint.OWNER_AD_VIEW, "c", "void",
                        DexKitZinstantFingerprint.AD_PARAM_COUNT, true, false));
        assertEquals("confidence_below_threshold", DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, noConstructor, HASH, HASH, false).status);
    }

    @Test
    public void wrongApkHashFailsClosed() {
        FingerprintResolver.Resolution resolution = evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND, "c",
                DexKitZinstantFingerprint.AD_PARAM_COUNT);
        assertEquals("c", resolution.symbol);
        FingerprintResolver.Resolution mismatched = DexKitZinstantFingerprint.evaluate(
                DexKitZinstantFingerprint.ANCHOR_AD_BIND,
                Collections.singletonList(hit(DexKitZinstantFingerprint.OWNER_AD_VIEW, "c",
                        "void", DexKitZinstantFingerprint.AD_PARAM_COUNT)),
                HASH, "other", false);
        assertEquals("apk_hash_mismatch", mismatched.status);
    }

    private FingerprintResolver.Resolution evaluate(String anchor, String name, int params) {
        String owner = DexKitZinstantFingerprint.expectedOwner(anchor);
        return DexKitZinstantFingerprint.evaluate(anchor,
                Collections.singletonList(hit(owner, name, "void", params)), HASH, HASH, true);
    }

    private DexKitZinstantFingerprint.MethodHit hit(String owner, String name, String returns,
                                                    int params) {
        return new DexKitZinstantFingerprint.MethodHit(owner, name, returns, params, true, true);
    }
}
