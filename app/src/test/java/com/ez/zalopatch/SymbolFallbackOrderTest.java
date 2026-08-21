package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * When no profile covers the installed release, neighbouring bundled profiles are preflighted in
 * this order and the first whose anchors resolve backs the hooks (Decision 15). Distance is the
 * only signal available before preflight runs, so the order decides which mapping is tried against
 * the live classloader first.
 */
public final class SymbolFallbackOrderTest {
    private static final long V_26_06_02 = 260602901L;
    private static final long V_26_07_01 = 260701901L;
    private static final long V_26_08_01 = 260801903L;

    @Test
    public void nearestMappedReleaseIsTriedFirst() {
        // An unmapped release just after 26.08.01, the realistic case: Zalo shipped, nobody remapped
        // yet, and the newest mapping is the closest thing to it.
        List<Long> codes = codes(V_26_06_02, V_26_07_01, V_26_08_01);

        SymbolSchema.orderByVersionDistance(codes, 260900001L);

        assertEquals(Arrays.asList(V_26_08_01, V_26_07_01, V_26_06_02), codes);
    }

    @Test
    public void anOlderInstalledReleasePrefersTheOlderMapping() {
        List<Long> codes = codes(V_26_06_02, V_26_07_01, V_26_08_01);

        SymbolSchema.orderByVersionDistance(codes, 260600001L);

        assertEquals(Arrays.asList(V_26_06_02, V_26_07_01, V_26_08_01), codes);
    }

    @Test
    public void anInstalledReleaseBetweenTwoMappingsTakesTheNearer() {
        List<Long> codes = codes(V_26_07_01, V_26_08_01);

        SymbolSchema.orderByVersionDistance(codes, V_26_08_01 - 10L);

        assertEquals(Arrays.asList(V_26_08_01, V_26_07_01), codes);
    }

    @Test
    public void anExactTieBreaksTowardTheNewerRelease() {
        // Equidistant mappings either side. Zalo carries anchors forward more often than it
        // reinstates an older shape, so the newer mapping is the better first guess.
        List<Long> codes = codes(100L, 300L);

        SymbolSchema.orderByVersionDistance(codes, 200L);

        assertEquals(Arrays.asList(300L, 100L), codes);
    }

    private static List<Long> codes(Long... values) {
        return new ArrayList<>(Arrays.asList(values));
    }
}
