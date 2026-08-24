package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PasscodeGraceFeatureTest {
    @Test
    public void longerChoiceReplacesNativeValue() {
        assertEquals(300000, PasscodeGraceFeature.effectiveResult(30000, 300000));
    }

    @Test
    public void matchingChoiceLeavesNativeValueUnchanged() {
        assertEquals(30000, PasscodeGraceFeature.effectiveResult(30000, 30000));
    }

    @Test
    public void absentOrDisabledChoiceLeavesNativeValueUnchanged() {
        assertEquals(30000, PasscodeGraceFeature.effectiveResult(30000, 0));
    }

    @Test
    public void chosenDurationReplacesWhateverZaloStored() {
        assertEquals(300000, PasscodeGraceFeature.effectiveResult(5000, 300000));
        assertEquals(3600000, PasscodeGraceFeature.effectiveResult(30000, 3600000));
        assertEquals(30000, PasscodeGraceFeature.effectiveResult(5000, 30000));
    }
}
