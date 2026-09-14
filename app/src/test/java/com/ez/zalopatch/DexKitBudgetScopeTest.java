package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the scope-bound retry budget (finding 6). */
public final class DexKitBudgetScopeTest {
    private static final String SCOPE_A = DexKitPilotPolicy.budgetScope(
            260802903L, 1726051200000L, 1, 2, 204);
    private static final String SCOPE_B = DexKitPilotPolicy.budgetScope(
            260802904L, 1726051200000L, 1, 2, 204);

    @Test
    public void scopeKeyIsDeterministicAndSensitive() {
        assertEquals(SCOPE_A, DexKitPilotPolicy.budgetScope(
                260802903L, 1726051200000L, 1, 2, 204));
        assertFalse(SCOPE_A.equals(SCOPE_B));
        assertFalse(SCOPE_A.equals(DexKitPilotPolicy.budgetScope(
                260802903L, 1726051200001L, 1, 2, 204)));
        assertFalse(SCOPE_A.equals(DexKitPilotPolicy.budgetScope(
                260802903L, 1726051200000L, 1, 2, 205)));
    }

    @Test
    public void freshScopeIsAlwaysAllowed() {
        DexKitStore.ScanState state = DexKitPilotPolicy.evaluateBudget(
                "old-scope", 3, 1000L, 0L, SCOPE_A, 2000L);
        assertTrue(state.allowed);
        assertEquals(0, state.failures);
    }

    @Test
    public void sameScopeAccumulatesThenBacksOff() {
        long now = 10_000L;
        DexKitStore.ScanState attempt =
                DexKitPilotPolicy.evaluateBudget(SCOPE_A, 2, now - 1000L, 0L, SCOPE_A, now);
        assertTrue(attempt.allowed);
        DexKitStore.ScanState blocked =
                DexKitPilotPolicy.evaluateBudget(SCOPE_A, 3, now - 1000L, 0L, SCOPE_A, now);
        assertFalse(blocked.allowed);
        assertEquals(3, blocked.failures);
        assertEquals("retry budget exhausted", blocked.reason);
        DexKitStore.ScanState expired = DexKitPilotPolicy.evaluateBudget(SCOPE_A, 3,
                now - DexKitStore.FAILURE_BACKOFF_MS - 1L, 0L, SCOPE_A, now);
        assertTrue(expired.allowed);
    }

    @Test
    public void heldSlotBlocksWithoutConsumingBudget() {
        long now = 10_000L;
        DexKitStore.ScanState state = DexKitPilotPolicy.evaluateBudget(
                SCOPE_A, 1, now - 1000L, now - 1000L, SCOPE_A, now);
        assertFalse(state.allowed);
        assertEquals(1, state.failures);
        assertEquals("scan already in progress", state.reason);
        DexKitStore.ScanState released = DexKitPilotPolicy.evaluateBudget(
                SCOPE_A, 1, now - 1000L, now - DexKitStore.SCAN_MARKER_TTL_MS - 1L, SCOPE_A, now);
        assertTrue(released.allowed);
    }

    @Test
    public void absentScopeNeverAuthorizes() {
        assertFalse(DexKitPilotPolicy.evaluateBudget(SCOPE_A, 0, 0L, 0L, "", 1000L).allowed);
        assertFalse(DexKitPilotPolicy.evaluateBudget("", 0, 0L, 0L, null, 1000L).allowed);
    }
}
