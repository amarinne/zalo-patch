package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The artifact gate matches on exact versionCode plus Zalo signing certificate. Google Play serves
 * per-device bundle variants and re-stamps its signing block per download, so a single release has
 * several base APK hashes over byte-identical code. Zalo 26.08.01 (260801903) was observed with
 * base APK hashes afd9aa96 (Play), 84f6700b and a3c19cf7 (APKMirror variants) whose classes*.dex
 * are identical.
 */
public final class ZaloArtifactGateTest {
    private static final String SIGNER =
            "d86efe151e09bf4ca8440cb3bfa0a81be2544f70c78587daf0266dfca2fa25df";
    private static final String MAPPED_APK =
            "afd9aa96e7f4beb772ad1632d17f5fe4a6bd12c1e3ce5978a6b2ec43ac9d2a57";
    private static final String VARIANT_APK =
            "84f6700bb2ac5017d4cd39d01042bee68e8d54e2ab8a11a6e60240eacf0c81c6";

    @Test
    public void mappedContainerReportsExactApkEvidence() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, SIGNER, MAPPED_APK, false);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_EXACT_APK, decision.evidence);
        assertEquals("", decision.error);
    }

    @Test
    public void otherContainerOfSameReleaseStaysReadyAsUnverifiedMatch() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, SIGNER, VARIANT_APK, false);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_VERSION_SIGNER, decision.evidence);
        assertEquals("", decision.error);
    }

    @Test
    public void foreignSignerIsRejected() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, "00" + SIGNER.substring(2), MAPPED_APK,
                false);

        assertEquals("mismatch", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_NONE, decision.evidence);
    }

    @Test
    public void lspatchResignedInstallStaysReadyAsLspatchResignedEvidence() {
        // LSPatch always resigns the patched APK with its own key, so the observed signer never
        // matches the original Zalo signer on a patched install. The loader-injected
        // *.lspatch.documents provider is what actually distinguishes this from a foreign repack.
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                true, "", "cached", SIGNER, MAPPED_APK, "00" + SIGNER.substring(2), MAPPED_APK,
                true);

        assertEquals("ready", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_LSPATCH_RESIGNED, decision.evidence);
        assertEquals("", decision.error);
    }

    @Test
    public void authorizationSurvivesAnInstallChangeThatKeepsTheProfile() {
        // An on-demand split install moves the install identity but not the profile. Play adds
        // feature splits (mediapipe_faceeffect, tensorflowLite) whenever a surface first needs
        // them, and devices differ in which ones they carry on one Zalo release.
        assertTrue(ZaloArtifactState.authorizes("ready", true, SIGNER, SIGNER, "profile", "profile"));
    }

    @Test
    public void aProfileTheHookCannotResolveIsNotAuthorized() {
        assertFalse(ZaloArtifactState.authorizes("ready", false, SIGNER, SIGNER, "", "profile"));
    }

    @Test
    public void aVersionMoveChangesTheProfileHashAndDeauthorizes() {
        assertFalse(ZaloArtifactState.authorizes(
                "ready", true, SIGNER, SIGNER, "new-version-profile", "old-version-profile"));
    }

    @Test
    public void pendingReconciliationIsNotAuthorized() {
        assertFalse(ZaloArtifactState.authorizes(
                "pending", true, SIGNER, SIGNER, "profile", "profile"));
    }

    @Test
    public void versionWithoutProfileIsUnsupported() {
        ZaloArtifactState.Decision decision = ZaloArtifactState.decide(
                false, "no exact profile for installed Zalo", "missing",
                "", "", SIGNER, VARIANT_APK, false);

        assertEquals("unsupported", decision.status);
        assertEquals(ZaloArtifactState.EVIDENCE_NONE, decision.evidence);
        assertEquals("no exact profile for installed Zalo; catalog missing", decision.error);
    }
}
