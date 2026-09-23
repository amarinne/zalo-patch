package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * forHooks authorizes through HookConfig, which reads the property mirror first. Every key it
 * reads must ride the reconcile-time mirror refresh, or the hook process keeps the previous
 * profile hash while the provider gate (preferences-direct) passes — catalog sequence 28 left
 * the mirror on the old hash and every self-check row went stale until sync_properties ran.
 */
public final class TweakStoreArtifactMirrorTest {
    @Test
    public void mirrorCoversEveryKeyForHooksReads() {
        Set<String> mirrored = new HashSet<>(Arrays.asList(TweakStore.ARTIFACT_MIRROR_KEYS));

        assertTrue(mirrored.contains(ZaloArtifactState.KEY_STATUS));
        assertTrue(mirrored.contains(ZaloArtifactState.KEY_LIGHTWEIGHT));
        assertTrue(mirrored.contains(ZaloArtifactState.KEY_GENERATION));
        assertTrue(mirrored.contains(ZaloArtifactState.KEY_PROFILE_SHA256));
        assertTrue(mirrored.contains(ZaloArtifactState.KEY_EVIDENCE));
        assertTrue(mirrored.contains(ZaloArtifactState.KEY_ERROR));
    }
}
