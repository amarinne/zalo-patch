package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SymbolCatalogCacheTest {
    @Test
    public void acceptsFirstEntryAndNewerRevision() {
        assertTrue(SymbolCatalogCache.canReplace(null, entry(14, "first")));
        assertTrue(SymbolCatalogCache.canReplace(entry(14, "first"), entry(15, "next")));
    }

    @Test
    public void rejectsRollbackAndConflictingSameRevision() {
        assertFalse(SymbolCatalogCache.canReplace(entry(15, "current"), entry(14, "old")));
        assertFalse(SymbolCatalogCache.canReplace(entry(15, "current"), entry(15, "different")));
    }

    @Test
    public void acceptsIdempotentRetryOfSameSignedPayload() {
        assertTrue(SymbolCatalogCache.canReplace(entry(15, "same"), entry(15, "same")));
    }

    private static SymbolCatalogContract.Entry entry(int sequence, String digest) {
        return new SymbolCatalogContract.Entry(sequence, digest, "{}", new byte[0]);
    }
}
