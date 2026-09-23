package com.ez.zalopatch;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** JVM tests for the DexKit property-mirror transport codec and binding rules. */
public final class DexKitMirrorTest {
    private static final String DIGEST =
            "eb41b236b129f8bac71b1df4ebe91c0b3e795376426468fb39b8bcfd3f0264c8";

    @Test
    public void budgetCodecRoundTrips() {
        DexKitMirror.Budget budget = new DexKitMirror.Budget("260901903:1:1:3:210", 2, 99L);
        DexKitMirror.Budget parsed = DexKitMirror.decodeBudget(
                DexKitMirror.encodeBudget(budget));
        assertNotNull(parsed);
        assertEquals("260901903:1:1:3:210", parsed.scope);
        assertEquals(2, parsed.failures);
        assertEquals(99L, parsed.lastAttemptMs);
    }

    @Test
    public void budgetCodecRejectsMalformed() {
        assertNull(DexKitMirror.decodeBudget(null));
        assertNull(DexKitMirror.decodeBudget(""));
        assertNull(DexKitMirror.decodeBudget("only-scope"));
        assertNull(DexKitMirror.decodeBudget("scope\nnotanint\n99"));
        assertNull(DexKitMirror.decodeBudget("scope\n-1\n99"));
        assertNull(DexKitMirror.decodeBudget("scope\n2\n-5"));
        assertNull(DexKitMirror.decodeBudget("scope\n2\n99\nextra"));
        assertEquals("", DexKitMirror.encodeBudget(
                new DexKitMirror.Budget("has\nnewline", 0, 0L)));
    }

    @Test
    public void mirrorBindsMatchesCheapIdentity() {
        DexKitCache.Entry entry = entry();
        assertTrue(DexKitMirror.mirrorBinds(entry, 260901903L, 7L, 68157440L, 1, 3, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901904L, 7L, 68157440L, 1, 3, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901903L, 8L, 68157440L, 1, 3, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901903L, 7L, 68157441L, 1, 3, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901903L, 7L, 68157440L, 2, 3, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901903L, 7L, 68157440L, 1, 4, 210));
        assertFalse(DexKitMirror.mirrorBinds(entry, 260901903L, 7L, 68157440L, 1, 3, 211));
        assertFalse(DexKitMirror.mirrorBinds(null, 260901903L, 7L, 68157440L, 1, 3, 210));
    }

    @Test
    public void mirrorBindsRequiresValidDigest() {
        DexKitCache.Entry broken = new DexKitCache.Entry(260901903L, "not-a-digest", 7L,
                68157440L, 1, 3, 210, "c", "c", false, "", 1, 1, 10L, 20L, 0, null);
        assertFalse(DexKitMirror.mirrorBinds(broken, 260901903L, 7L, 68157440L, 1, 3, 210));
    }

    @Test
    public void blobRoundTripThroughFake() {
        Map<String, String> store = new HashMap<>();
        DexKitMirror.BlobIo io = fake(store);
        store.put(DexKitMirror.MIRROR_CACHE_KEY, DexKitCache.serialize(entry()));
        store.put(DexKitMirror.MIRROR_BUDGET_KEY,
                DexKitMirror.encodeBudget(new DexKitMirror.Budget("scope-a", 1, 5L)));
        DexKitCache.Entry parsed = DexKitMirror.readCacheEntry(io);
        assertNotNull(parsed);
        assertEquals("c", parsed.adBind);
        DexKitMirror.Budget budget = DexKitMirror.readBudget(io);
        assertNotNull(budget);
        assertEquals("scope-a", budget.scope);
        assertEquals(1, budget.failures);
    }

    @Test
    public void blobReadToleratesAbsentAndCorrupt() {
        DexKitMirror.BlobIo empty = fake(new HashMap<String, String>());
        assertNull(DexKitMirror.readCacheEntry(empty));
        assertNull(DexKitMirror.readBudget(empty));
        Map<String, String> corrupt = new HashMap<>();
        corrupt.put(DexKitMirror.MIRROR_CACHE_KEY, "{\"version_code\":1}");
        corrupt.put(DexKitMirror.MIRROR_BUDGET_KEY, "nope");
        DexKitMirror.BlobIo io = fake(corrupt);
        assertNull(DexKitMirror.readCacheEntry(io));
        assertNull(DexKitMirror.readBudget(io));
    }

    @Test
    public void seedBudgetResetsOnScopeChange() {
        DexKitMirror.Budget kept = DexKitMirror.seedBudget("scope-a", 2, 50L, "scope-a");
        assertEquals("scope-a", kept.scope);
        assertEquals(2, kept.failures);
        assertEquals(50L, kept.lastAttemptMs);
        DexKitMirror.Budget reset = DexKitMirror.seedBudget("scope-a", 2, 50L, "scope-b");
        assertEquals("scope-b", reset.scope);
        assertEquals(0, reset.failures);
        assertEquals(0L, reset.lastAttemptMs);
        DexKitMirror.Budget first = DexKitMirror.seedBudget("", 0, 0L, "scope-b");
        assertEquals("scope-b", first.scope);
        assertEquals("", DexKitMirror.encodeBudget(
                DexKitMirror.seedBudget("", 0, 0L, "")));
    }

    @Test
    public void mirrorClaimEnforcesBudgetWithoutSlot() {
        String scope = "260901903:7:1:3:210";
        DexKitMirror.Claim fresh = DexKitMirror.claimFromMirror(null, scope, 1000L);
        assertTrue(fresh.allowed);
        DexKitMirror.Claim exhausted = DexKitMirror.claimFromMirror(
                new DexKitMirror.Budget(scope, 3, 999L), scope, 1000L);
        assertFalse(exhausted.allowed);
        assertEquals("retry budget exhausted", exhausted.reason);
        DexKitMirror.Claim moved = DexKitMirror.claimFromMirror(
                new DexKitMirror.Budget("other-scope", 3, 999L), scope, 1000L);
        assertTrue(moved.allowed);
    }

    private DexKitCache.Entry entry() {
        return new DexKitCache.Entry(260901903L, DIGEST, 7L, 68157440L, 1, 3, 210,
                "c", "c", false, "", 1, 1, 10L, 20L, 0, null);
    }

    private DexKitMirror.BlobIo fake(final Map<String, String> store) {
        return new DexKitMirror.BlobIo() {
            @Override
            public String read(String key) {
                return store.get(key);
            }

            @Override
            public boolean write(String key, String value) {
                store.put(key, value);
                return true;
            }
        };
    }
}
