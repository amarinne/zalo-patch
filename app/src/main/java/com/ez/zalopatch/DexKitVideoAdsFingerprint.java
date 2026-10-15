package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure shapes for independently qualified, dedicated Zalo Video ad views. */
public final class DexKitVideoAdsFingerprint {
    public static final String CONTENT = "com.zing.zalo.shortvideo.domain.entity.content.Content";
    public static final String PLAYLIST = "com.zing.zalo.shortvideo.domain.entity.PlaylistInfo";

    public enum Route {
        OUTSTREAM("outstream", "OutstreamAdsLayout", "void", "", "boolean"),
        TEMPLATE("template", "AdsTemplateLayout", "void", "", CONTENT),
        NATIVE("native", "AdsNativeLayout", "long", "getStartTimeShow"),
        PHOTO_ITEM("photo_item", "AdvertisingPhotoItem", CONTENT, "getAdvertisingContent"),
        VIDEO_ITEM("video_item", "AdvertisingVideoItem", CONTENT, "getAdvertisingContent");

        public final String id;
        public final String ownerSuffix;
        public final String returnType;
        public final String semanticMethod;
        public final List<String> parameters;

        Route(String id, String ownerSuffix, String returnType, String semanticMethod,
              String... parameters) {
            this.id = id;
            this.ownerSuffix = ownerSuffix;
            this.returnType = returnType;
            this.semanticMethod = semanticMethod;
            this.parameters = Collections.unmodifiableList(Arrays.asList(parameters));
        }

        public String family() { return "video_ads_" + id; }
        public String query() { return "video_ads." + id; }
        public String anchor(String leaf) { return "symbols." + family() + "." + leaf; }
        public String row() { return "video_ads." + id; }
        public boolean item() { return this == PHOTO_ITEM || this == VIDEO_ITEM; }
        public boolean implementations() { return this == TEMPLATE || this == NATIVE; }
        public String[] anchors() {
            return item() ? new String[]{anchor("view_class"), anchor("marker_method"),
                    anchor("bind_method"), anchor("payload_bind_method")}
                    : this == NATIVE ? new String[]{anchor("view_class"), anchor("marker_method"),
                        anchor("bind_method"), anchor("implementation_classes")}
                    : this == TEMPLATE ? new String[]{anchor("view_class"), anchor("marker_method"),
                        anchor("bind_method"), anchor("payload_bind_method"), anchor("implementation_classes")}
                        : new String[]{anchor("view_class"), anchor("marker_method")};
        }
        public boolean dedicatedOwner(String owner) {
            return owner != null && owner.startsWith("com.zing.zalo.shortvideo.ui.")
                    && owner.endsWith("." + ownerSuffix);
        }
    }

    private DexKitVideoAdsFingerprint() { }

    public static List<String> strings(Route route) {
        if (route == Route.OUTSTREAM) return Arrays.asList(
                "outstream_ads_close", "outstream_ads_skip", "skip_ads_second");
        if (route == Route.TEMPLATE) return Collections.singletonList("cta_ad_show");
        return Collections.emptyList();
    }

    public static final class MethodShape {
        public final String name;
        public final String returns;
        public final List<String> parameters;
        public final boolean isStatic;
        public final boolean isAbstract;

        public MethodShape(String name, String returns, List<String> parameters,
                           boolean isStatic, boolean isAbstract) {
            this.name = name == null ? "" : name;
            this.returns = returns == null ? "" : returns;
            this.parameters = parameters == null ? Collections.emptyList()
                    : new ArrayList<>(parameters);
            this.isStatic = isStatic;
            this.isAbstract = isAbstract;
        }
    }

    public static final class Candidate {
        public final String owner;
        public final MethodShape marker;
        public final boolean concreteView;
        public final List<MethodShape> declaredMethods;
        public final List<Candidate> implementations;

        public Candidate(String owner, MethodShape marker, boolean concreteView,
                         List<MethodShape> declaredMethods) {
            this(owner, marker, concreteView, declaredMethods, Collections.emptyList());
        }

        public Candidate(String owner, MethodShape marker, boolean concreteView,
                         List<MethodShape> declaredMethods, List<Candidate> implementations) {
            this.owner = owner == null ? "" : owner;
            this.marker = marker;
            this.concreteView = concreteView;
            this.declaredMethods = declaredMethods == null ? Collections.emptyList()
                    : new ArrayList<>(declaredMethods);
            this.implementations = new ArrayList<>(implementations);
        }
    }

    public static final class Resolution {
        public final Map<String, String> descriptors;
        public final String status;

        Resolution(Map<String, String> descriptors, String status) {
            this.descriptors = Collections.unmodifiableMap(descriptors);
            this.status = status;
        }
        public boolean resolved() { return "resolved".equals(status); }
    }

    /** Missing or truncated query results cannot establish uniqueness. */
    public static Resolution evaluate(Route route, List<Candidate> candidates, boolean complete) {
        if (!complete) return miss("query_error");
        Map<String, Map<String, String>> qualified = new LinkedHashMap<>();
        if (candidates != null) for (Candidate candidate : candidates) {
            Map<String, String> shape = shape(route, candidate);
            if (!shape.isEmpty()) {
                String key = candidate.owner + "#" + candidate.marker.name;
                qualified.put(key, shape);
            }
        }
        if (qualified.isEmpty()) return miss("no_qualified_concrete_view");
        if (qualified.size() != 1) return miss("ambiguous_ad_view");
        return new Resolution(qualified.values().iterator().next(), "resolved");
    }

    /** Pure preflight shared by discovery and live hook arming. */
    public static Map<String, String> shape(Route route, Candidate candidate) {
        Map<String, String> descriptors = new LinkedHashMap<>();
        if (candidate == null || (!candidate.concreteView && !route.implementations())
                || (candidate.concreteView && route.implementations())
                || !route.dedicatedOwner(candidate.owner)
                || !instance(candidate.marker) || !candidate.marker.returns.equals(route.returnType)
                || !candidate.marker.parameters.equals(route.parameters)
                || (!route.semanticMethod.isEmpty()
                    && !route.semanticMethod.equals(candidate.marker.name))) return descriptors;
        int markerNames = 0;
        for (MethodShape method : candidate.declaredMethods) {
            if (method.name.equals(candidate.marker.name)) markerNames++;
        }
        if (markerNames != 1) return descriptors;
        if (route.item()) {
            MethodShape bind = uniqueBind(candidate.declaredMethods, false);
            MethodShape payload = uniqueBind(candidate.declaredMethods, true);
            if (bind == null || payload == null
                    || !bind.parameters.get(3).equals(payload.parameters.get(4))) return descriptors;
            descriptors.put(route.anchor("bind_method"), bind.name);
            descriptors.put(route.anchor("payload_bind_method"), payload.name);
        }
        if (route.implementations()) {
            MethodShape binder = familyBind(route, candidate.declaredMethods, false, true);
            MethodShape payload = route == Route.TEMPLATE
                    ? familyBind(route, candidate.declaredMethods, true, true) : null;
            if (binder == null || (route == Route.TEMPLATE && payload == null)
                    || candidate.implementations.isEmpty() || candidate.implementations.size() > 8) {
                return new LinkedHashMap<>();
            }
            List<String> owners = new ArrayList<>();
            for (Candidate implementation : candidate.implementations) {
                if (!implementation.concreteView || owners.contains(implementation.owner)
                        || !sameOverride(binder, implementation.declaredMethods)
                        || (payload != null && !sameOverride(payload, implementation.declaredMethods))) {
                    return new LinkedHashMap<>();
                }
                owners.add(implementation.owner);
            }
            Collections.sort(owners);
            descriptors.put(route.anchor("bind_method"), binder.name);
            if (payload != null) descriptors.put(route.anchor("payload_bind_method"), payload.name);
            descriptors.put(route.anchor("implementation_classes"), String.join(",", owners));
        }
        descriptors.put(route.anchor("view_class"), candidate.owner);
        descriptors.put(route.anchor("marker_method"), candidate.marker.name);
        return descriptors;
    }

    private static Resolution miss(String status) {
        return new Resolution(new LinkedHashMap<>(), status);
    }

    private static boolean instance(MethodShape method) {
        return method != null && !method.name.isEmpty() && !method.isStatic && !method.isAbstract;
    }

    private static MethodShape familyBind(Route route, List<MethodShape> methods,
                                          boolean payload, boolean abstractMethod) {
        MethodShape chosen = null;
        for (MethodShape method : methods) {
            List<String> p = method.parameters;
            if (method.isStatic || method.isAbstract != abstractMethod || !"void".equals(method.returns)
                    || p.size() != (route == Route.TEMPLATE && !payload ? 4 : 2)) continue;
            String first = route == Route.NATIVE
                    ? "com.zing.zalo.shortvideo.domain.entity.ads.AdsNativeData$AdsNativeInfo" : CONTENT;
            if (!first.equals(p.get(0)) || (p.size() == 4 && !"java.lang.String".equals(p.get(1)))
                    || !referenceType(p.get(p.size() - 1))
                    || (p.size() == 4 && !referenceType(p.get(2)))) continue;
            int sameName = 0;
            for (MethodShape other : methods) if (method.name.equals(other.name)) sameName++;
            if (sameName != 1 || chosen != null) return null;
            chosen = method;
        }
        return chosen;
    }

    private static boolean referenceType(String type) {
        return type.contains(".") && !type.startsWith("java.") && !type.startsWith("android.");
    }

    private static boolean sameOverride(MethodShape base, List<MethodShape> methods) {
        MethodShape found = null;
        for (MethodShape method : methods) if (base.name.equals(method.name)) {
            if (found != null) return false;
            found = method;
        }
        return instance(found) && base.returns.equals(found.returns)
                && base.parameters.equals(found.parameters);
    }

    private static MethodShape uniqueBind(List<MethodShape> methods, boolean payload) {
        MethodShape chosen = null;
        for (MethodShape method : methods) {
            List<String> p = method.parameters;
            if (!instance(method) || !"void".equals(method.returns) || p.size() != 7) continue;
            boolean matches = CONTENT.equals(p.get(0)) && CONTENT.equals(p.get(5))
                    && PLAYLIST.equals(p.get(6))
                    && (payload ? "java.util.List".equals(p.get(1))
                        && "java.lang.String".equals(p.get(2)) && "int".equals(p.get(3))
                        : "java.lang.String".equals(p.get(1)) && "int".equals(p.get(2))
                            && "boolean".equals(p.get(4)));
            String state = p.get(payload ? 4 : 3);
            if (!matches || !state.contains(".") || state.startsWith("java.")
                    || state.startsWith("android.")) continue;
            // Names are the persisted anchor vocabulary; overloaded names cannot be bound safely.
            int sameName = 0;
            for (MethodShape other : methods) if (other.name.equals(method.name)) sameName++;
            if (sameName != 1 || chosen != null) return null;
            chosen = method;
        }
        return chosen;
    }
}
