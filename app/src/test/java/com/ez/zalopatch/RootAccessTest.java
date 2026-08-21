package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class RootAccessTest {
    @Test
    public void rootUidIsGranted() {
        assertEquals(RootAccess.State.GRANTED, RootAccess.classify(
                new DiagnosticRootProcessRunner.Result(0, "0\n", false, false)));
    }

    @Test
    public void nonRootOrDeniedCommandIsDenied() {
        assertEquals(RootAccess.State.DENIED, RootAccess.classify(
                new DiagnosticRootProcessRunner.Result(1, "permission denied", false, false)));
        assertEquals(RootAccess.State.DENIED, RootAccess.classify(
                new DiagnosticRootProcessRunner.Result(0, "2000\n", false, false)));
    }

    @Test
    public void missingSuBinaryIsAbsent() {
        assertEquals(RootAccess.State.ABSENT, RootAccess.classify(
                new DiagnosticRootProcessRunner.Result(-1, "", false, false)));
    }

    @Test
    public void timeoutIsDeniedRatherThanReprobedRepeatedly() {
        assertEquals(RootAccess.State.DENIED, RootAccess.classify(
                new DiagnosticRootProcessRunner.Result(-1, "", true, false)));
    }
}
