package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class DexKitStoryFingerprintTest {
    private static final List<String> SEEN = Arrays.asList("int", "java.lang.String");
    private static final List<String> SUGGESTED = Arrays.asList("int", "int", "java.lang.String");

    private static DexKitStoryFingerprint.MethodHit hit(String owner, String method,
            List<String> parameters, String returnType, boolean isStatic, int command,
            boolean storyId, boolean parsesStoryId, boolean writesPayload) {
        return new DexKitStoryFingerprint.MethodHit(owner, method, returnType, parameters,
                isStatic, command, storyId, parsesStoryId, writesPayload);
    }

    private static DexKitStoryFingerprint.MethodHit ordinary() {
        // Exact 261001905 APK: command 1343, storyId, Long.parseLong, payload write.
        return hit("ap.b", "r", SEEN, "void", false, 1343, true, true, true);
    }

    private static DexKitStoryFingerprint.MethodHit suggested() {
        // Exact 261001905 APK: command 1356 with the extra suggested-story integer.
        return hit("ap.b", "v", SUGGESTED, "void", false, 1356, true, true, true);
    }

    @Test public void octoberGoldenRequestsResolveIndependently() {
        List<DexKitStoryFingerprint.MethodHit> evidence = Arrays.asList(ordinary(), suggested());
        DexKitStoryFingerprint.Resolution seen = DexKitStoryFingerprint.evaluate(false, evidence);
        DexKitStoryFingerprint.Resolution suggested = DexKitStoryFingerprint.evaluate(true, evidence);
        assertEquals("ap.b", seen.owner);
        assertEquals("r", seen.method);
        assertEquals("ap.b", suggested.owner);
        assertEquals("v", suggested.method);
    }

    @Test public void aRenameNeedsNoPinnedOwnerOrMethod() {
        DexKitStoryFingerprint.Resolution found = DexKitStoryFingerprint.evaluate(false,
                Collections.singletonList(hit("rotated.Owner", "send", SEEN, "void", false,
                        1343, true, true, true)));
        assertTrue(found.resolved());
        assertEquals("rotated.Owner", found.owner);
    }

    @Test public void aMissingSuggestedRouteDoesNotClaimItResolved() {
        List<DexKitStoryFingerprint.MethodHit> hits = Collections.singletonList(ordinary());
        assertTrue(DexKitStoryFingerprint.evaluate(false, hits).resolved());
        assertEquals("no_request", DexKitStoryFingerprint.evaluate(true, hits).status);
    }

    @Test public void commandAndStoryMarkerAreBothRequired() {
        assertFalse(DexKitStoryFingerprint.evaluate(false, Collections.singletonList(
                hit("host.Request", "other", SEEN, "void", false, 1344, true, true, true))).resolved());
        assertFalse(DexKitStoryFingerprint.evaluate(false, Collections.singletonList(
                hit("host.Request", "other", SEEN, "void", false, 1343, false, true, true))).resolved());
    }

    @Test public void serializationAndExactInstanceShapeAreRequired() {
        List<DexKitStoryFingerprint.MethodHit> decoys = Arrays.asList(
                hit("host.Request", "a", SEEN, "void", false, 1343, true, false, true),
                hit("host.Request", "b", SEEN, "void", false, 1343, true, true, false),
                hit("host.Request", "c", SEEN, "boolean", false, 1343, true, true, true),
                hit("host.Request", "d", SEEN, "void", true, 1343, true, true, true),
                hit("host.Request", "e", SUGGESTED, "void", false, 1343, true, true, true));
        assertEquals("no_request", DexKitStoryFingerprint.evaluate(false, decoys).status);
    }

    @Test public void multipleQualifyingRequestsFailClosed() {
        DexKitStoryFingerprint.Resolution ambiguous = DexKitStoryFingerprint.evaluate(false,
                Arrays.asList(ordinary(), hit("other.Request", "r", SEEN, "void", false,
                        1343, true, true, true)));
        assertEquals("ambiguous_request", ambiguous.status);
        assertEquals("", ambiguous.owner);
        assertEquals("", ambiguous.method);
    }

    @Test public void emptyAndNullEvidenceStayUnavailable() {
        assertFalse(DexKitStoryFingerprint.evaluate(false, null).resolved());
        assertFalse(DexKitStoryFingerprint.evaluate(true, Arrays.asList(null, ordinary())).resolved());
    }

    public static class Request {
        public void ordinary(int source, String storyId) { }
        public void suggested(int first, int second, String storyId) { }
        public static void staticRequest(int source, String storyId) { }
        public int returning(int source, String storyId) { return 1; }
        public native void nativeRequest(int source, String storyId);
        private void privateRequest(int source, String storyId) { }
    }
    public static final class Inherited extends Request { }
    public abstract static class AbstractRequest {
        public abstract void ordinary(int source, String storyId);
    }

    @Test public void livePreflightAcceptsOnlyDeclaredPublicInstanceVoidRequests() {
        assertNotNull(DexKitStoryFingerprint.requestMethod(Request.class, "ordinary", false));
        assertNotNull(DexKitStoryFingerprint.requestMethod(Request.class, "suggested", true));
        for (String name : Arrays.asList("staticRequest", "returning", "nativeRequest",
                "privateRequest", "missing", "suggested")) {
            assertNull(name, DexKitStoryFingerprint.requestMethod(Request.class, name, false));
        }
        assertNull(DexKitStoryFingerprint.requestMethod(Inherited.class, "ordinary", false));
        assertNull(DexKitStoryFingerprint.requestMethod(AbstractRequest.class, "ordinary", false));
        assertNull(DexKitStoryFingerprint.requestMethod(null, "ordinary", false));
    }
}
