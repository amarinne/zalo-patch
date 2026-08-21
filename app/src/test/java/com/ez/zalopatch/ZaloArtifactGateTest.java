package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The artifact gate matches on exact versionCode. Container identity is reported as a tier, not
 * gated.
 *
 * <p>Google Play serves per-device bundle variants and re-stamps its signing block per download, so
 * a single release has several base APK hashes over byte-identical code. Zalo 26.08.01 (260801903)
 * was observed with base APK hashes afd9aa96 (Play), 84f6700b and a3c19cf7 (APKMirror variants)
 * whose classes*.dex are identical (Decision 14).
 *
 * <p>The signer is likewise not a gate (Decision 15). Re-signing rezips without rebuilding dex, so
 * clone tools and mirror redistributions carry a fresh signer over unchanged code, and repackaging
 * does not re-obfuscate. Signer 2d6eeb20 was observed against versionCode 260801903.
 */
public final class ZaloArtifactGateTest {
    private static final String SIGNER =
            "d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df";
    private static final String MAPPED_APK =
            "afd9aa96e7f4beb772ad1632d17f5fe4a6bd12c1e3ce5978a6b2ec43ac9d2a57";
    private static final String VARIANT_APK =
            "84f6700bb2ac5017d4cd39d01042bee68e8d54e2ab8a11a6e60240eacf0c81c6";
    private static final String FOREIGN_SIGNER =
            "2d6eeb20365289e16e7be662f6aaa06682f4ebf6725c6f865916a720e0e277b8";

    @Test
    public void mappedContainerReportsExactApkEvidence() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, SIGNER, MAPPED_APK);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_EXACT_APK, decision.evidence);
        assertEquals("", decision.error);
    }

    @Test
    public void otherContainerOfSameReleaseStaysReadyAsUnverifiedMatch() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, SIGNER, VARIANT_APK);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_VERSION_SIGNER, decision.evidence);
        assertEquals("", decision.error);
    }

    @Test
    public void foreignSignerStaysReadyAsVersionOnlyMatch() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, FOREIGN_SIGNER, VARIANT_APK);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_VERSION_ONLY, decision.evidence);
        assertEquals("", decision.error);
    }

    /**
     * A re-signed container that kept the mapped base APK hash cannot occur in practice, since
     * re-signing rewrites the archive. Pinned anyway so the signer branch is what selects the tier.
     */
    @Test
    public void foreignSignerOutranksAMatchingBaseHash() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, FOREIGN_SIGNER, MAPPED_APK);

        assertEquals(ZaloArtifactState.EVIDENCE_VERSION_ONLY, decision.evidence);
    }

    @Test
    public void authorizationSurvivesAnInstallChangeThatKeepsTheProfile() {
        // An on-demand split install moves the install identity but not the profile. Play adds
        // feature splits (mediapipe_faceeffect, tensorflowLite) whenever a surface first needs
        // them, and devices differ in which ones they carry on one Zalo release.
        assertTrue(ZaloArtifactState.authorizes("ready", true, "profile", "profile"));
    }

    @Test
    public void aProfileTheHookCannotResolveIsNotAuthorized() {
        assertFalse(ZaloArtifactState.authorizes("ready", false, "", "profile"));
    }

    @Test
    public void aVersionMoveChangesTheProfileHashAndDeauthorizes() {
        assertFalse(ZaloArtifactState.authorizes(
                "ready", true, "new-version-profile", "old-version-profile"));
    }

    @Test
    public void pendingReconciliationIsNotAuthorized() {
        assertFalse(ZaloArtifactState.authorizes(
                "pending", true, "profile", "profile"));
    }

    @Test
    public void versionWithoutProfileIsUnsupported() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                false, "no exact profile for installed Zalo", "missing",
                "", "", SIGNER, VARIANT_APK);

        assertEquals("unsupported", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_NONE, decision.evidence);
        assertEquals("no exact profile for installed Zalo; catalog missing", decision.error);
    }
}
