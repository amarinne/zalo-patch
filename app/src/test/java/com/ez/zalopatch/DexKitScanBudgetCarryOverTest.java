package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Regression guard for the stuck-scan bug: a scope change must discard the previous
 * generation's slot and attempt timestamp. Carrying them over made the new scope look
 * like a scan already in progress, so the claim was refused and the process, which had
 * already spent its one attempt, reported pending forever.
 */
public final class DexKitScanBudgetCarryOverTest {
    @Test
    public void newScopeDiscardsPreviousSlotAndAttempt() {
        long[] carried = DexKitPilotPolicy.budgetCarryOver(
                "old-scope", "new-scope", 111L, 222L);
        assertEquals(0L, carried[0]);
        assertEquals(0L, carried[1]);
    }

    @Test
    public void sameScopeKeepsItsLiveSlot() {
        long[] carried = DexKitPilotPolicy.budgetCarryOver(
                "same", "same", 111L, 222L);
        assertEquals(111L, carried[0]);
        assertEquals(222L, carried[1]);
    }

    @Test
    public void absentOrNullScopeNeverCarriesOver() {
        assertEquals(0L, DexKitPilotPolicy.budgetCarryOver("old", "", 5L, 6L)[0]);
        assertEquals(0L, DexKitPilotPolicy.budgetCarryOver("old", null, 5L, 6L)[0]);
    }
}
