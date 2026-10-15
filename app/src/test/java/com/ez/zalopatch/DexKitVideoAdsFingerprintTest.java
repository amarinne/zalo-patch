package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;
import static com.ez.zalopatch.DexKitVideoAdsFingerprint.*;

public class DexKitVideoAdsFingerprintTest {
    private static MethodShape method(String name, String returns, String... parameters) {
        return new MethodShape(name, returns, Arrays.asList(parameters), false, false);
    }

    private static Candidate candidate(Route route, boolean concrete, MethodShape... more) {
        MethodShape marker = new MethodShape(route.semanticMethod.isEmpty() ? "bind" : route.semanticMethod,
                route.returnType, route.parameters, false, false);
        List<MethodShape> methods = new java.util.ArrayList<>(Arrays.asList(more));
        methods.add(marker);
        return new Candidate("com.zing.zalo.shortvideo.ui.ads." + route.ownerSuffix,
                marker, concrete, methods);
    }

    private static MethodShape bind(String name) {
        return method(name, "void", CONTENT, "java.lang.String", "int", "state.Model", "boolean", CONTENT, PLAYLIST);
    }

    private static MethodShape payload(String name) {
        return method(name, "void", CONTENT, "java.util.List", "java.lang.String", "int", "state.Model", CONTENT, PLAYLIST);
    }

    @Test public void allOutstreamMarkersAreRequired() {
        assertEquals(Arrays.asList("outstream_ads_close", "outstream_ads_skip", "skip_ads_second"),
                strings(Route.OUTSTREAM));
        assertTrue(evaluate(Route.OUTSTREAM, Collections.singletonList(candidate(Route.OUTSTREAM, true)), true).resolved());
        assertFalse(evaluate(Route.OUTSTREAM, Collections.singletonList(candidate(Route.OUTSTREAM, true)), false).resolved());
    }

    private static Candidate family(Route route, Candidate... implementations) {
        MethodShape binder = route == Route.NATIVE
                ? method("a", "void", "com.zing.zalo.shortvideo.domain.entity.ads.AdsNativeData$AdsNativeInfo", "callback.Type")
                : method("a", "void", CONTENT, "java.lang.String", "state.Model", "callback.Type");
        List<MethodShape> methods = new java.util.ArrayList<>();
        methods.add(new MethodShape(binder.name, binder.returns, binder.parameters, false, true));
        if (route == Route.TEMPLATE) methods.add(new MethodShape("b", "void",
                Arrays.asList(CONTENT, "callback.Payload"), false, true));
        Candidate base = candidate(route, false, methods.toArray(new MethodShape[0]));
        return new Candidate(base.owner, base.marker, false, base.declaredMethods, Arrays.asList(implementations));
    }

    private static Candidate implementation(Route route, String name) {
        List<MethodShape> methods = new java.util.ArrayList<>();
        for (MethodShape method : family(route).declaredMethods) {
            if (method.isAbstract) methods.add(new MethodShape(method.name, method.returns,
                    method.parameters, false, false));
        }
        return new Candidate("com.zing.zalo.shortvideo.ui.ads." + name, null, true, methods);
    }

    @Test public void abstractFamiliesRequireCompleteConcreteImplementations() {
        assertFalse(evaluate(Route.TEMPLATE, Collections.singletonList(candidate(Route.TEMPLATE, false)), true).resolved());
        assertFalse(evaluate(Route.NATIVE, Collections.singletonList(candidate(Route.NATIVE, false)), true).resolved());
        assertFalse(evaluate(Route.TEMPLATE, Collections.singletonList(candidate(Route.TEMPLATE, true)), true).resolved());
        assertFalse(evaluate(Route.NATIVE, Collections.singletonList(candidate(Route.NATIVE, true)), true).resolved());
        for (Route route : Arrays.asList(Route.TEMPLATE, Route.NATIVE)) {
            Candidate base = family(route, implementation(route, "LayoutZ"), implementation(route, "LayoutA"));
            Resolution result = evaluate(route, Collections.singletonList(base), true);
            assertTrue(result.status, result.resolved());
            assertEquals("com.zing.zalo.shortvideo.ui.ads.LayoutA,com.zing.zalo.shortvideo.ui.ads.LayoutZ",
                    result.descriptors.get(route.anchor("implementation_classes")));
            assertEquals("a", result.descriptors.get(route.anchor("bind_method")));
            if (route == Route.TEMPLATE) assertEquals("b", result.descriptors.get(route.anchor("payload_bind_method")));
            assertFalse(evaluate(route, Collections.singletonList(base), false).resolved());
            assertFalse(evaluate(route, Collections.singletonList(family(route)), true).resolved());
        }
    }

    @Test public void changedMissingStaticAbstractAndOverloadedOverridesFailClosed() {
        for (Route route : Arrays.asList(Route.TEMPLATE, Route.NATIVE)) {
            Candidate good = implementation(route, "LayoutA");
            MethodShape bind = good.declaredMethods.get(0);
            List<List<MethodShape>> invalid = Arrays.asList(
                    Collections.emptyList(),
                    Collections.singletonList(method(bind.name, "void", CONTENT, "wrong.Callback")),
                    Collections.singletonList(new MethodShape(bind.name, bind.returns, bind.parameters, true, false)),
                    Collections.singletonList(new MethodShape(bind.name, bind.returns, bind.parameters, false, true)),
                    Arrays.asList(bind, method(bind.name, "void", "int")));
            for (List<MethodShape> methods : invalid) {
                Candidate bad = new Candidate(good.owner, null, true, methods);
                assertFalse(evaluate(route, Collections.singletonList(family(route, bad)), true).resolved());
            }
            assertFalse(evaluate(route, Collections.singletonList(family(route, good, good)), true).resolved());
            Candidate nonconcrete = new Candidate(good.owner, null, false, good.declaredMethods);
            assertFalse(evaluate(route, Collections.singletonList(family(route, nonconcrete)), true).resolved());
            Candidate[] many = new Candidate[9];
            for (int i = 0; i < many.length; i++) many[i] = implementation(route, "Layout" + i);
            assertFalse(evaluate(route, Collections.singletonList(family(route, many)), true).resolved());
        }
    }

    @Test public void interfacesNonviewsAndOrdinaryVideoOwnersAreRejected() {
        Candidate good = candidate(Route.VIDEO_ITEM, true, bind("bindContent"), payload("bindPayload"));
        assertFalse(evaluate(Route.VIDEO_ITEM, Collections.singletonList(new Candidate("cr0.a0", good.marker, false,
                good.declaredMethods)), true).resolved());
        assertFalse(evaluate(Route.VIDEO_ITEM, Collections.singletonList(new Candidate(
                "com.zing.zalo.shortvideo.ui.ads.NormalVideoItem", good.marker, true, good.declaredMethods)), true).resolved());
        assertFalse(evaluate(Route.VIDEO_ITEM, Collections.singletonList(candidate(Route.VIDEO_ITEM, false,
                bind("bindContent"), payload("bindPayload"))), true).resolved());
    }

    @Test public void eachItemHasItsOwnCompleteBindingContract() {
        Candidate photo = candidate(Route.PHOTO_ITEM, true, bind("bindContent"), payload("bindPayload"));
        Candidate video = candidate(Route.VIDEO_ITEM, true, bind("bindVideo"), payload("bindVideoPayload"));
        assertEquals(4, evaluate(Route.PHOTO_ITEM, Arrays.asList(photo, video), true).descriptors.size());
        assertEquals("bindVideo", evaluate(Route.VIDEO_ITEM, Arrays.asList(photo, video), true)
                .descriptors.get(Route.VIDEO_ITEM.anchor("bind_method")));
        assertFalse(evaluate(Route.PHOTO_ITEM, Collections.singletonList(candidate(Route.PHOTO_ITEM, true,
                bind("onlyBind"))), true).resolved());
    }

    @Test public void ambiguousOwnersAndOverloadedNamesFailClosed() {
        Candidate first = candidate(Route.OUTSTREAM, true);
        Candidate second = new Candidate("com.zing.zalo.shortvideo.ui.other.OutstreamAdsLayout", first.marker,
                true, first.declaredMethods);
        assertEquals("ambiguous_ad_view", evaluate(Route.OUTSTREAM, Arrays.asList(first, second), true).status);
        assertFalse(evaluate(Route.OUTSTREAM, Collections.singletonList(candidate(Route.OUTSTREAM, true,
                method("bind", "void", "int"))), true).resolved());
        assertFalse(evaluate(Route.VIDEO_ITEM, Collections.singletonList(candidate(Route.VIDEO_ITEM, true,
                bind("one"), bind("two"), payload("payload"))), true).resolved());
    }

    @Test public void changedStateRelationshipAndStaticMarkersAreRejected() {
        Candidate video = candidate(Route.VIDEO_ITEM, true, bind("contentBind"),
                method("payloadBind", "void", CONTENT, "java.util.List", "java.lang.String", "int", "other.Model", CONTENT, PLAYLIST));
        assertFalse(evaluate(Route.VIDEO_ITEM, Collections.singletonList(video), true).resolved());
        Candidate first = candidate(Route.OUTSTREAM, true);
        MethodShape staticMarker = new MethodShape("bind", "void", first.marker.parameters, true, false);
        assertFalse(evaluate(Route.OUTSTREAM, Collections.singletonList(new Candidate(first.owner, staticMarker,
                true, Collections.singletonList(staticMarker))), true).resolved());
    }

    @Test public void anchorsAndRetryFamiliesRoundTripForEveryRoute() {
        java.util.Map<String, String> descriptors = new java.util.LinkedHashMap<>();
        for (Route route : Route.values()) {
            assertTrue(DexKitFamilyRetry.FAMILIES.contains(route.family()));
            for (String anchor : route.anchors()) {
                assertNotNull(DexKitAnchors.find(anchor));
                assertEquals(route.family(), DexKitFamilyRetry.familyOf(anchor));
                descriptors.put(anchor, anchor.endsWith("implementation_classes") ? "ad.LayoutA,ad.LayoutB"
                        : anchor.endsWith("view_class") ? "com.zing.zalo.shortvideo.ui.ads." + route.ownerSuffix : "bind");
            }
        }
        assertEquals(descriptors, DexKitAnchors.parse(DexKitAnchors.serialize(descriptors)));
    }

    @Test public void implementationListsRejectNoncanonicalAndUnboundedValues() {
        DexKitAnchors.Anchor anchor = DexKitAnchors.find(Route.NATIVE.anchor("implementation_classes"));
        assertTrue(DexKitAnchors.validDescriptor(anchor, "ad.LayoutA,ad.LayoutB"));
        for (String value : Arrays.asList("", "ad.LayoutB,ad.LayoutA", "ad.LayoutA,ad.LayoutA",
                "ad.LayoutA,", "ad.LayoutA, ad.LayoutB", "ad.LayoutA;evil.Class", "ad.LayoutA=evil.Class",
                "ad.A,ad.B,ad.C,ad.D,ad.E,ad.F,ad.G,ad.H,ad.I")) {
            assertFalse(value, DexKitAnchors.validDescriptor(anchor, value));
        }
    }
}
