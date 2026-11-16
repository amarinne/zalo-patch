package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** JVM tests for the WebView-externalize DexKit fingerprints. */
public final class DexKitWebviewFingerprintTest {
    private static final String DIGEST = "x";

    @Test
    public void redirectResolvesSingleStaticUriMethod() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(redirect("A8"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateRedirect(hits, DIGEST, DIGEST, false);
        assertEquals("resolved", resolution.status);
        assertEquals("A8", resolution.symbol);
    }

    @Test
    public void redirectRejectsWrongShape() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(new DexKitWebviewFingerprint.MethodHit(
                DexKitWebviewFingerprint.OWNER_WEB_VIEW, "k", "void",
                Arrays.asList("android.net.Uri"), true, false, false));
        hits.add(new DexKitWebviewFingerprint.MethodHit(
                "com.other.View", "A8", "android.net.Uri",
                Arrays.asList("android.net.Uri"), true, false, false));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateRedirect(hits, DIGEST, DIGEST, false);
        assertFalse("resolved".equals(resolution.status));
    }

    @Test
    public void redirectAmbiguousStaysUnavailable() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(redirect("A8"));
        hits.add(redirect("A9"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateRedirect(hits, DIGEST, DIGEST, false);
        assertEquals("ambiguous_margin", resolution.status);
    }

    @Test
    public void companionResolvesSingleOwningClass() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("com.zing.zalo.ui.zviews.vt", "k"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateCompanion(hits, DIGEST, DIGEST, false);
        assertEquals("resolved", resolution.status);
        assertEquals("com.zing.zalo.ui.zviews.vt", resolution.symbol);
    }

    @Test
    public void companionIgnoresForeignPackageAndWebView() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("com.zing.zalo.ui.zviews.vt", "k"));
        hits.add(dispatch("com.zing.zalo.other.Foo", "k"));
        hits.add(dispatch(DexKitWebviewFingerprint.OWNER_WEB_VIEW, "k"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateCompanion(hits, DIGEST, DIGEST, false);
        assertEquals("resolved", resolution.status);
        assertEquals("com.zing.zalo.ui.zviews.vt", resolution.symbol);
    }

    @Test
    public void companionAmbiguousAcrossOwners() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("com.zing.zalo.ui.zviews.vt", "k"));
        hits.add(dispatch("com.zing.zalo.ui.zviews.xt", "k"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateCompanion(hits, DIGEST, DIGEST, false);
        assertFalse("resolved".equals(resolution.status));
    }

    @Test
    public void companionFieldResolvesTheStableViewCompanion() {
        // 261102901: ZaloWebView.Companion is e3w, and two owners carry a dispatch shape,
        // so the global scan alone is ambiguous while the stable field is exact.
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("e3w", "g"));
        hits.add(dispatch("other.Companion", "k"));
        assertFalse("resolved".equals(DexKitWebviewFingerprint.evaluateCompanion(
                hits, DIGEST, DIGEST, false).status));
        FingerprintResolver.Resolution field =
                DexKitWebviewFingerprint.evaluateCompanionField(
                        "e3w", hits, DIGEST, DIGEST, false);
        assertEquals("resolved", field.status);
        assertEquals("e3w", field.symbol);
    }

    @Test
    public void companionFieldFailsClosedWithoutEvidence() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("e3w", "g"));
        assertEquals("no_companion_field", DexKitWebviewFingerprint.evaluateCompanionField(
                "", hits, DIGEST, DIGEST, false).status);
        assertEquals("no_companion_field", DexKitWebviewFingerprint.evaluateCompanionField(
                null, hits, DIGEST, DIGEST, false).status);
        assertEquals("companion_self", DexKitWebviewFingerprint.evaluateCompanionField(
                DexKitWebviewFingerprint.OWNER_WEB_VIEW, hits, DIGEST, DIGEST, false).status);
        assertEquals("no_dispatch_on_companion",
                DexKitWebviewFingerprint.evaluateCompanionField(
                        "e3w", new ArrayList<DexKitWebviewFingerprint.MethodHit>(),
                        DIGEST, DIGEST, false).status);
        List<DexKitWebviewFingerprint.MethodHit> doubled = new ArrayList<>();
        doubled.add(dispatch("e3w", "g"));
        doubled.add(dispatch("e3w", "h"));
        assertEquals("ambiguous_dispatch_on_companion",
                DexKitWebviewFingerprint.evaluateCompanionField(
                        "e3w", doubled, DIGEST, DIGEST, false).status);
    }

    @Test
    public void dispatchResolvesOnKnownCompanion() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("com.zing.zalo.ui.zviews.vt", "k"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateDispatch("com.zing.zalo.ui.zviews.vt",
                        hits, DIGEST, DIGEST, false);
        assertEquals("resolved", resolution.status);
        assertEquals("k", resolution.symbol);
    }

    @Test
    public void dispatchWithoutCompanionStaysUnavailable() {
        List<DexKitWebviewFingerprint.MethodHit> hits = new ArrayList<>();
        hits.add(dispatch("com.zing.zalo.ui.zviews.vt", "k"));
        FingerprintResolver.Resolution resolution =
                DexKitWebviewFingerprint.evaluateDispatch("", hits, DIGEST, DIGEST, false);
        assertFalse("resolved".equals(resolution.status));
    }

    @Test
    public void dispatchShapeRequiresInterfacesAtBothEnds() {
        DexKitWebviewFingerprint.MethodHit plain = new DexKitWebviewFingerprint.MethodHit(
                "com.zing.zalo.ui.zviews.vt", "k", "void",
                Arrays.asList("java.lang.Object", "java.lang.String", "android.os.Bundle",
                        "boolean", "int", "java.lang.Object"),
                true, false, false);
        assertFalse(DexKitWebviewFingerprint.isDispatchShape(plain));
        assertFalse(DexKitWebviewFingerprint.isDispatchShape(null));
    }

    private DexKitWebviewFingerprint.MethodHit redirect(String name) {
        return new DexKitWebviewFingerprint.MethodHit(
                DexKitWebviewFingerprint.OWNER_WEB_VIEW, name, "android.net.Uri",
                Arrays.asList("android.net.Uri"), true, false, false);
    }

    private DexKitWebviewFingerprint.MethodHit dispatch(String owner, String name) {
        return new DexKitWebviewFingerprint.MethodHit(owner, name, "void",
                Arrays.asList("com.zing.zalo.SomeCallback", "java.lang.String",
                        "android.os.Bundle", "boolean", "int", "com.zing.zalo.OtherCallback"),
                true, true, true);
    }
}
