package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Target retention for INST-STATE-1: original plus two clones keep separate mutable artifact,
 * catalog-deadline, runtime-evidence, job and diagnostic identities. Android storage is a fake
 * ContextWrapper, so this is static/host coverage only.
 */
public final class InstanceStateEvidenceTest {
    private static final String CLONE_A = "vn.example.insta";
    private static final String CLONE_B = "vn.example.instb";
    private static final long ZALO_VERSION = 260801903L;

    @Test public void registeredTargetsRouteCanonicalOriginalAndRevokedClones() {
        FrameworkConfigPublicationTest.TestContext module = module();
        assertNull(ZaloArtifactState.registeredTarget(module, null));
        assertNull(ZaloArtifactState.registeredTarget(module, ""));
        assertNull(ZaloArtifactState.registeredTarget(module, CLONE_A));
        assertEquals(HostPackages.ZALO,
                ZaloArtifactState.registeredTarget(module, HostPackages.ZALO));
        assertNull(ZaloArtifactState.eventTarget(module, "com.ez.zalopatch"));
        assertNull(ZaloArtifactState.eventTarget(module, "android"));
        assertNull(ZaloArtifactState.eventTarget(module, CLONE_A + ".clone"));
        assertNull(ZaloArtifactState.eventTarget(module, "com.zing.zalo;id"));

        assertTrue(HostPackages.save(module, CLONE_A + "\n" + CLONE_B));
        assertEquals(CLONE_A, ZaloArtifactState.eventTarget(module, CLONE_A));
        assertEquals(CLONE_B, ZaloArtifactState.eventTarget(module, CLONE_B));
        assertNull(ZaloArtifactState.eventTarget(module, CLONE_A + ".clone"));
        assertNull(ZaloArtifactState.eventTarget(module, "vn.example.other"));

        // Removing one registration must revoke it on the next route, without touching the other.
        assertTrue(HostPackages.save(module, CLONE_B));
        assertNull(ZaloArtifactState.eventTarget(module, CLONE_A));
        assertEquals(CLONE_B, ZaloArtifactState.eventTarget(module, CLONE_B));
    }

    @Test public void jobTargetAndContextNeverFallBackToOriginal() {
        FrameworkConfigPublicationTest.TestContext module = module();
        assertTrue(HostPackages.save(module, CLONE_A + "\n" + CLONE_B));

        Context cloneA = ZaloArtifactState.jobContext(module, CLONE_A);
        assertNotNull(cloneA);
        assertEquals(CLONE_A, HostPackages.transportHost(cloneA));
        assertEquals(CLONE_A, ZaloArtifactState.jobTarget(module, CLONE_A));

        assertNull(ZaloArtifactState.jobContext(module, null));
        assertNull(ZaloArtifactState.jobContext(module, ""));
        assertNull(ZaloArtifactState.jobContext(module, CLONE_A + ".clone"));
        assertNull(ZaloArtifactState.jobContext(module, "com.ez.zalopatch"));

        Context original = ZaloArtifactState.jobContext(module, HostPackages.ZALO);
        assertNotNull(original);
        assertEquals(HostPackages.ZALO, HostPackages.transportHost(original));

        int originalJob = ZaloArtifactState.jobId(HostPackages.ZALO);
        int cloneAJob = ZaloArtifactState.jobId(CLONE_A);
        int cloneBJob = ZaloArtifactState.jobId(CLONE_B);
        assertEquals(originalJob, ZaloArtifactState.jobId(HostPackages.ZALO));
        assertEquals(cloneAJob, ZaloArtifactState.jobId(CLONE_A));
        assertNotEquals(originalJob, cloneAJob);
        assertNotEquals(cloneAJob, cloneBJob);
        assertNotEquals(originalJob, cloneBJob);
    }

    @Test public void lightweightCacheIsRetainedAndInvalidatedPerTarget() {
        FrameworkConfigPublicationTest.TestContext module = module();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);

        ZaloArtifactState.rememberLightweight(module, "original");
        ZaloArtifactState.rememberLightweight(cloneA, "clone-a");
        ZaloArtifactState.rememberLightweight(cloneB, "clone-b");

        assertEquals("original", ZaloArtifactState.rememberedLightweight(module));
        assertEquals("clone-a", ZaloArtifactState.rememberedLightweight(cloneA));
        assertEquals("clone-b", ZaloArtifactState.rememberedLightweight(cloneB));
        assertNotEquals(ZaloArtifactState.rememberedLightweight(module),
                ZaloArtifactState.rememberedLightweight(cloneA));
    }

    @Test public void catalogDeadlinesAndMutableStateStayPerScope() {
        FrameworkConfigPublicationTest.TestContext module = module();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);
        SharedPreferences original = TweakStore.preferences(module);
        SharedPreferences first = TweakStore.preferences(cloneA);
        SharedPreferences second = TweakStore.preferences(cloneB);

        assertTrue(original.edit()
                .putLong(ZaloArtifactState.KEY_CATALOG_CHECKED_AT, 111L)
                .putLong(ZaloArtifactState.KEY_CATALOG_RETRY_AT, 1111L)
                .commit());
        assertTrue(first.edit()
                .putLong(ZaloArtifactState.KEY_CATALOG_CHECKED_AT, 222L)
                .commit());

        assertEquals(111L, original.getLong(
                ZaloArtifactState.KEY_CATALOG_CHECKED_AT, 0L));
        assertEquals(222L, first.getLong(ZaloArtifactState.KEY_CATALOG_CHECKED_AT, 0L));
        assertEquals(0L, second.getLong(ZaloArtifactState.KEY_CATALOG_CHECKED_AT, 0L));
        assertEquals(0L, first.getLong(ZaloArtifactState.KEY_CATALOG_RETRY_AT, 0L));
        assertEquals(1111L, original.getLong(ZaloArtifactState.KEY_CATALOG_RETRY_AT, 0L));

        // Immutable catalog content is read from the module scope; mutable deadlines stay scoped.
        assertSame(module, ZaloArtifactState.catalogStore(module));
        assertSame(module, ZaloArtifactState.catalogStore(cloneA));
        assertSame(module, ZaloArtifactState.catalogStore(cloneB));
    }

    @Test public void runtimeEvidenceIsPerScopeAndVersionBound() {
        FrameworkConfigPublicationTest.TestContext module = module();
        Context cloneA = HostPackages.scoped(module, CLONE_A);
        Context cloneB = HostPackages.scoped(module, CLONE_B);

        assertTrue(RuntimeEnvironment.record(cloneA, "lspatch", "observed",
                BuildConfig.VERSION_CODE, ZALO_VERSION));
        RuntimeEnvironment.Snapshot first = RuntimeEnvironment.current(cloneA, ZALO_VERSION);
        assertTrue(first.reported);
        assertEquals(RuntimeEnvironment.Framework.LSPATCH, first.framework);
        assertTrue(first.resourceHooksObserved);
        assertFalse(RuntimeEnvironment.current(module, ZALO_VERSION).reported);
        assertFalse(RuntimeEnvironment.current(cloneB, ZALO_VERSION).reported);

        assertTrue(RuntimeEnvironment.record(module, "lsposed", "pending",
                BuildConfig.VERSION_CODE, ZALO_VERSION));
        assertEquals(RuntimeEnvironment.Framework.LSPOSED,
                RuntimeEnvironment.current(module, ZALO_VERSION).framework);
        assertEquals(RuntimeEnvironment.Framework.LSPATCH,
                RuntimeEnvironment.current(cloneA, ZALO_VERSION).framework);
        assertEquals(RuntimeEnvironment.Framework.UNKNOWN,
                RuntimeEnvironment.current(cloneB, ZALO_VERSION).framework);

        // A different installed Zalo version is Not checked, never the previous target's evidence.
        assertFalse(RuntimeEnvironment.current(cloneA, ZALO_VERSION + 1L).reported);
        assertTrue(RuntimeEnvironment.current(cloneA, ZALO_VERSION).reported);
    }

    @Test public void renamedArtifactsKeepVersionOnlyProvenance() {
        ZaloArtifactState.Decision ready = ZaloArtifactState.decideRenamed(
                true, "", "cached");
        assertEquals("ready", ready.status);
        assertEquals(ZaloArtifactState.EVIDENCE_VERSION_ONLY, ready.evidence);
        assertEquals("", ready.error);
        assertNotEquals(ZaloArtifactState.EVIDENCE_EXACT_APK, ready.evidence);
        assertNotEquals(ZaloArtifactState.EVIDENCE_VERSION_SIGNER, ready.evidence);

        ZaloArtifactState.Decision unsupported = ZaloArtifactState.decideRenamed(
                false, "no exact profile", "missing");
        assertEquals("unsupported", unsupported.status);
        assertEquals(ZaloArtifactState.EVIDENCE_NONE, unsupported.evidence);
        assertTrue(unsupported.error.contains("no exact profile"));
    }

    private static FrameworkConfigPublicationTest.TestContext module() {
        return new FrameworkConfigPublicationTest.TestContext();
    }
}
