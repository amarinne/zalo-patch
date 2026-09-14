package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared DexKit fingerprint definition for the Zinstant ad-bind pilot.
 *
 * <p>This is the single definition consumed by both offline checks and the on-device resolver.
 * It is deliberately dependency-free (no DexKit, no Android): the runtime adapter converts DexKit
 * {@code MethodData} into {@link MethodHit}, and JVM tests construct hits directly, so the
 * mandatory predicates below are verified without a native library.
 *
 * <p>Pilot anchors (non-recording, low-risk, small dependency set):
 * <ul>
 *   <li>{@code zinstant.ad_bind_method}: void method with 3 parameters, owned by the stable
 *       public view {@code com.zing.zalo.ui.widget.ZinstantAdItemView}.</li>
 *   <li>{@code zinstant.feed_bind_method}: void method with 4 parameters, owned by the stable
 *       public view {@code com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds}.</li>
 * </ul>
 *
 * <p>Mandatory predicates (every one must hold; score never compensates):
 * <ol>
 *   <li>The owner equals the stable public view class above. An obfuscated name, a candidate
 *       score, or structural shape alone is never semantic proof.</li>
 *   <li>Return type is {@code void}.</li>
 *   <li>Parameter count is exactly 3 (message) or 4 (feed).</li>
 *   <li>The owner is an {@code android.view.View} subtype with at least one constructor
 *       (the "constructor plus void binding" fingerprint). At runtime this is checked by loading
 *       the stable owner; offline fixtures carry the flag explicitly.</li>
 * </ol>
 *
 * <p>Scoring reuses {@link FingerprintResolver}: zero or multiple qualifying candidates stay
 * unavailable ({@code stale}); the winner must clear the normal threshold with the required
 * margin. Flag mapping is explicit: stable owner -&gt; {@code stableOwner}, all mandatory
 * predicates -&gt; {@code mandatorySemantic}, void-plus-arity -&gt; {@code methodShape},
 * constructor presence -&gt; {@code fieldRelations}, View boundary -&gt;
 * {@code frameworkReferences}, retained-version survival -&gt; {@code bothGoldenVersions}.
 *
 * <p>Public because the hook-process preflight ({@code SymbolPreflight}) and resolver live in
 * another package; the definition itself stays a closed vocabulary of constants and predicates.
 */
public final class DexKitZinstantFingerprint {
    /** Revision of this query definition. Bumped whenever any predicate changes; bound to cache. */
    public static final int QUERY_REVISION = 1;

    public static final String ANCHOR_AD_BIND = "zinstant.ad_bind_method";
    public static final String ANCHOR_FEED_BIND = "zinstant.feed_bind_method";

    public static final String OWNER_AD_VIEW =
            "com.zing.zalo.ui.widget.ZinstantAdItemView";
    public static final String OWNER_FEED_ADS =
            "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds";

    public static final String RETURN_VOID = "void";
    public static final int AD_PARAM_COUNT = 3;
    public static final int FEED_PARAM_COUNT = 4;

    private DexKitZinstantFingerprint() {
    }

    /** Minimal method shape shared by the DexKit adapter and offline fixtures. */
    public static final class MethodHit {
        public final String className;
        public final String methodName;
        public final String returnTypeName;
        public final int paramCount;
        public final boolean viewBoundary;
        public final boolean hasConstructor;

        public MethodHit(String className, String methodName, String returnTypeName, int paramCount,
                  boolean viewBoundary, boolean hasConstructor) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.returnTypeName = returnTypeName == null ? "" : returnTypeName;
            this.paramCount = paramCount;
            this.viewBoundary = viewBoundary;
            this.hasConstructor = hasConstructor;
        }
    }

    /** Evaluates one anchor against candidate hits. Never returns a partial mapping. */
    public static FingerprintResolver.Resolution evaluate(String anchor, List<MethodHit> hits,
                                                   String expectedApkSha256,
                                                   String actualApkSha256,
                                                   boolean retainedVersion) {
        int expectedParams = expectedParamCount(anchor);
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        if (hits != null) {
            for (MethodHit hit : hits) {
                if (hit == null) continue;
                boolean stableOwner = expectedOwner(anchor).equals(hit.className);
                boolean shape = RETURN_VOID.equals(hit.returnTypeName)
                        && hit.paramCount == expectedParams;
                boolean mandatory = stableOwner && shape && hit.viewBoundary && hit.hasConstructor;
                candidates.add(new FingerprintResolver.Candidate(
                        hit.methodName,
                        stableOwner,
                        mandatory,
                        shape,
                        hit.hasConstructor,
                        hit.viewBoundary,
                        retainedVersion && mandatory,
                        0));
            }
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256, anchor,
                false, candidates);
    }

    public static String expectedOwner(String anchor) {
        return ANCHOR_FEED_BIND.equals(anchor) ? OWNER_FEED_ADS : OWNER_AD_VIEW;
    }

    public static int expectedParamCount(String anchor) {
        return ANCHOR_FEED_BIND.equals(anchor) ? FEED_PARAM_COUNT : AD_PARAM_COUNT;
    }

    /**
     * Recorded bind names for every retained mapped APK, from the bundled exact profiles.
     * Used by golden tests and by comparison-mode logging; never used to resolve.
     */
    public static Map<Long, String[]> expectedDescriptors() {
        Map<Long, String[]> expected = new LinkedHashMap<>();
        expected.put(260602901L, new String[]{"i", "s"});
        expected.put(260701901L, new String[]{"c", "c"});
        expected.put(260801903L, new String[]{"c", "c"});
        expected.put(260802903L, new String[]{"c", "c"});
        return Collections.unmodifiableMap(expected);
    }
}
