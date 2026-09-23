package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** JVM tests for the passcode/backup string-anchored fingerprints. */
public final class DexKitPasscodeFingerprintTest {
    private static final String DIGEST = "x";

    @Test
    public void pairedReaderAndSetterResolve() {
        List<DexKitPasscodeFingerprint.CallerHit> callers = new ArrayList<>();
        callers.add(caller("a.b.C", "load", reader("v40.q0", "n"), setter("zz.j", "B3")));
        FingerprintResolver.Resolution reader =
                DexKitPasscodeFingerprint.evaluateReader(callers, DIGEST, DIGEST, false);
        FingerprintResolver.Resolution setter =
                DexKitPasscodeFingerprint.evaluateSetter(callers, "v40.q0#n",
                        DIGEST, DIGEST, false);
        assertEquals("resolved", reader.status);
        assertEquals("v40.q0#n", reader.symbol);
        assertEquals("resolved", setter.status);
        assertEquals("zz.j#B3", setter.symbol);
    }

    @Test
    public void splitCallersResolveReaderButNotSetter() {
        // Reads and writes live in different key-loading callers on the observed
        // artifact: the reader stays key-bound and unique, but no setter shares the
        // reader's caller, so the preflight-only setter stays unavailable.
        List<DexKitPasscodeFingerprint.CallerHit> callers = new ArrayList<>();
        callers.add(caller("a.b.C", "load", reader("v40.q0", "n")));
        callers.add(caller("a.b.D", "other", setter("zz.j", "B3")));
        FingerprintResolver.Resolution reader =
                DexKitPasscodeFingerprint.evaluateReader(callers, DIGEST, DIGEST, false);
        FingerprintResolver.Resolution setter =
                DexKitPasscodeFingerprint.evaluateSetter(callers, "v40.q0#n",
                        DIGEST, DIGEST, false);
        assertEquals("resolved", reader.status);
        assertEquals("v40.q0#n", reader.symbol);
        assertFalse("resolved".equals(setter.status));
    }

    @Test
    public void ambiguousSharingSettersStayUnavailable() {
        List<DexKitPasscodeFingerprint.CallerHit> callers = new ArrayList<>();
        callers.add(caller("a.b.C", "load", reader("v40.q0", "n"), setter("zz.j", "B3")));
        callers.add(caller("a.b.C", "load", reader("v40.q0", "n"), setter("zz.j", "B4")));
        FingerprintResolver.Resolution setter =
                DexKitPasscodeFingerprint.evaluateSetter(callers, "v40.q0#n",
                        DIGEST, DIGEST, false);
        assertFalse("resolved".equals(setter.status));
    }

    @Test
    public void ambiguousReaderStaysUnavailable() {
        List<DexKitPasscodeFingerprint.CallerHit> callers = new ArrayList<>();
        callers.add(caller("a.b.C", "load", reader("v40.q0", "n"), setter("zz.j", "B3")));
        callers.add(caller("a.b.E", "load", reader("v40.q1", "n"), setter("zz.j", "B3")));
        FingerprintResolver.Resolution reader =
                DexKitPasscodeFingerprint.evaluateReader(callers, DIGEST, DIGEST, false);
        assertFalse("resolved".equals(reader.status));
    }

    @Test
    public void noCallersStaysUnavailable() {
        FingerprintResolver.Resolution backup =
                DexKitPasscodeFingerprint.evaluateBackup(null, DIGEST, DIGEST, false);
        assertFalse("resolved".equals(backup.status));
    }

    @Test
    public void backupResolvesUniqueShape() {
        List<DexKitPasscodeFingerprint.CallerHit> callers = new ArrayList<>();
        callers.add(caller("a.b.F", "read", backup("v40.q0", "o")));
        FingerprintResolver.Resolution resolution =
                DexKitPasscodeFingerprint.evaluateBackup(callers, DIGEST, DIGEST, false);
        assertEquals("resolved", resolution.status);
        assertEquals("v40.q0#o", resolution.symbol);
    }

    @Test
    public void splitIdentityParsesOwnerAndName() {
        assertEquals("v40.q0", DexKitPasscodeFingerprint.splitIdentity("v40.q0#n")[0]);
        assertEquals("n", DexKitPasscodeFingerprint.splitIdentity("v40.q0#n")[1]);
        assertEquals("", DexKitPasscodeFingerprint.splitIdentity("malformed")[0]);
        assertEquals("", DexKitPasscodeFingerprint.splitIdentity(null)[1]);
    }

    private DexKitPasscodeFingerprint.CallerHit caller(String owner, String name,
            DexKitPasscodeFingerprint.Callee... invoked) {
        return new DexKitPasscodeFingerprint.CallerHit(owner, name, Arrays.asList(invoked));
    }

    private DexKitPasscodeFingerprint.Callee reader(String owner, String name) {
        return new DexKitPasscodeFingerprint.Callee(owner, name, "int",
                Arrays.asList("int", "java.lang.String", "boolean"), true);
    }

    private DexKitPasscodeFingerprint.Callee setter(String owner, String name) {
        return new DexKitPasscodeFingerprint.Callee(owner, name, "void",
                Arrays.asList("int"), false);
    }

    private DexKitPasscodeFingerprint.Callee backup(String owner, String name) {
        return new DexKitPasscodeFingerprint.Callee(owner, name, "long",
                Arrays.asList("long", "boolean", "java.lang.String"), true);
    }
}
