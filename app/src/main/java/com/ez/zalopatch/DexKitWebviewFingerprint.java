package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared DexKit fingerprint definition for the WebView-externalize family.
 *
 * <p>Dependency-free like {@link DexKitZinstantFingerprint}: the runtime adapter
 * converts DexKit {@code MethodData} into {@link MethodHit}, and JVM tests construct
 * hits directly.
 *
 * <p>Anchors:
 * <ul>
 *   <li>{@code webview.redirect_transform_method}: static {@code (Uri)->Uri} on the
 *       stable view {@code com.zing.zalo.ui.zviews.ZaloWebView}, exactly one.</li>
 *   <li>{@code webview.companion_class}: a class under
 *       {@code com.zing.zalo.ui.zviews} (excluding the view itself) owning exactly one
 *       dispatch-shaped method; exactly one such class.</li>
 *   <li>{@code webview.open_dispatch_method}: static {@code void} with six parameters
 *       {@code (interface, String, Bundle, boolean, int, interface)} on the resolved
 *       companion; exactly one. Interface positions are checked at runtime by loading
 *       the parameter types; middle positions by exact type name.</li>
 * </ul>
 *
 * <p>Scoring reuses {@link FingerprintResolver}: zero or multiple qualifying
 * candidates stay unavailable; the winner must clear the normal threshold with the
 * required margin.
 */
public final class DexKitWebviewFingerprint {
    public static final String ANCHOR_REDIRECT = "symbols.webview.redirect_transform_method";
    public static final String ANCHOR_COMPANION = "symbols.webview.companion_class";
    public static final String ANCHOR_DISPATCH = "symbols.webview.open_dispatch_method";

    public static final String OWNER_WEB_VIEW = "com.zing.zalo.ui.zviews.ZaloWebView";
    public static final String OWNER_PACKAGE = "com.zing.zalo.ui.zviews.";

    public static final String TYPE_URI = "android.net.Uri";
    public static final String TYPE_VOID = "void";
    public static final String TYPE_STRING = "java.lang.String";
    public static final String TYPE_BUNDLE = "android.os.Bundle";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_INT = "int";

    private DexKitWebviewFingerprint() {
    }

    /** Minimal method shape shared by the DexKit adapter and offline fixtures. */
    public static final class MethodHit {
        public final String className;
        public final String methodName;
        public final String returnTypeName;
        public final List<String> paramTypeNames;
        public final boolean isStatic;
        public final boolean firstParamInterface;
        public final boolean lastParamInterface;

        public MethodHit(String className, String methodName, String returnTypeName,
                         List<String> paramTypeNames, boolean isStatic,
                         boolean firstParamInterface, boolean lastParamInterface) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.returnTypeName = returnTypeName == null ? "" : returnTypeName;
            this.paramTypeNames = paramTypeNames == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypeNames);
            this.isStatic = isStatic;
            this.firstParamInterface = firstParamInterface;
            this.lastParamInterface = lastParamInterface;
        }
    }

    /** Evaluates the redirect anchor against candidate hits. */
    public static FingerprintResolver.Resolution evaluateRedirect(List<MethodHit> hits,
            String expectedApkSha256, String actualApkSha256, boolean retainedVersion) {
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        if (hits != null) {
            for (MethodHit hit : hits) {
                if (hit == null) continue;
                boolean stableOwner = OWNER_WEB_VIEW.equals(hit.className);
                boolean shape = hit.isStatic && TYPE_URI.equals(hit.returnTypeName)
                        && hit.paramTypeNames.size() == 1
                        && TYPE_URI.equals(hit.paramTypeNames.get(0));
                boolean mandatory = stableOwner && shape;
                candidates.add(new FingerprintResolver.Candidate(
                        hit.methodName, stableOwner, mandatory, shape,
                        true, true, retainedVersion && mandatory, 0));
            }
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_REDIRECT, false, candidates);
    }

    /** True when the hit has the dispatch method shape (owner checked separately). */
    public static boolean isDispatchShape(MethodHit hit) {
        if (hit == null || !hit.isStatic || !TYPE_VOID.equals(hit.returnTypeName)
                || hit.paramTypeNames.size() != 6) {
            return false;
        }
        return hit.firstParamInterface
                && TYPE_STRING.equals(hit.paramTypeNames.get(1))
                && TYPE_BUNDLE.equals(hit.paramTypeNames.get(2))
                && TYPE_BOOLEAN.equals(hit.paramTypeNames.get(3))
                && TYPE_INT.equals(hit.paramTypeNames.get(4))
                && hit.lastParamInterface;
    }

    /**
     * Evaluates the companion anchor: groups dispatch-shaped hits by owner package
     * class, keeps owners with exactly one such method, and requires exactly one
     * owner globally. Returns the winning owner as the symbol, or an unavailable
     * resolution.
     */
    public static FingerprintResolver.Resolution evaluateCompanion(List<MethodHit> hits,
            String expectedApkSha256, String actualApkSha256, boolean retainedVersion) {
        java.util.LinkedHashMap<String, List<String>> byOwner = new java.util.LinkedHashMap<>();
        if (hits != null) {
            for (MethodHit hit : hits) {
                if (!isDispatchShape(hit) || !hit.className.startsWith(OWNER_PACKAGE)
                        || OWNER_WEB_VIEW.equals(hit.className)) {
                    continue;
                }
                List<String> methods = byOwner.get(hit.className);
                if (methods == null) {
                    methods = new ArrayList<>();
                    byOwner.put(hit.className, methods);
                }
                if (!methods.contains(hit.methodName)) {
                    methods.add(hit.methodName);
                }
            }
        }
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        for (java.util.Map.Entry<String, List<String>> entry : byOwner.entrySet()) {
            boolean single = entry.getValue().size() == 1;
            candidates.add(new FingerprintResolver.Candidate(
                    entry.getKey(), true, single, single, true, true,
                    retainedVersion && single, 0));
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_COMPANION, false, candidates);
    }

    /**
     * Evaluates the dispatch anchor once the companion is known: exactly one
     * dispatch-shaped method on that owner.
     */
    public static FingerprintResolver.Resolution evaluateDispatch(String companion,
            List<MethodHit> hits, String expectedApkSha256, String actualApkSha256,
            boolean retainedVersion) {
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        if (companion != null && !companion.isEmpty() && hits != null) {
            for (MethodHit hit : hits) {
                if (!companion.equals(hit.className)) {
                    continue;
                }
                boolean mandatory = isDispatchShape(hit);
                candidates.add(new FingerprintResolver.Candidate(
                        hit.methodName, true, mandatory, mandatory, true, true,
                        retainedVersion && mandatory, 0));
            }
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_DISPATCH, false, candidates);
    }
}
