package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitZinstantFingerprint;

import java.util.ArrayList;
import java.util.List;

/**
 * Sole holder of direct DexKit references.
 *
 * <p>Isolation matters: if the native library or the DexKit classes are unavailable, only this
 * class fails to link, and the caller ({@code DexKitZinstantResolver}) catches that {@link
 * Throwable} and keeps the existing fallback policy. Nothing else in the module references
 * {@code org.luckypray.dexkit}.
 *
 * <p>One bridge per discovery session, closed deterministically via try-with-resources.
 * File-backed base-APK inspection only; loaded-memory DEX inspection is a separate extension
 * the pilot does not need (all pilot anchors live in the base APK on every mapped profile).
 */
final class DexKitBridgeRunner {
    private static boolean loadAttempted;
    private static String loadError = "";

    private DexKitBridgeRunner() {
    }

    /** Loads the native library once per process. Returns null on success, else the reason. */
    static synchronized String ensureLoaded() {
        if (loadAttempted) {
            return loadError.isEmpty() ? null : loadError;
        }
        loadAttempted = true;
        try {
            System.loadLibrary("dexkit");
            return null;
        } catch (Throwable throwable) {
            loadError = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return loadError;
        }
    }

    /** Raw method shapes from one owner-scoped query, plus the total match count. */
    static final class QueryResult {
        final List<RawHit> hits = new ArrayList<>();
        final int matchCount;
        final String error;

        QueryResult(int matchCount, String error) {
            this.matchCount = matchCount;
            this.error = error == null ? "" : error;
        }
    }

    static final class RawHit {
        final String className;
        final String methodName;
        final String returnTypeName;
        final int paramCount;

        RawHit(String className, String methodName, String returnTypeName, int paramCount) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.returnTypeName = returnTypeName == null ? "" : returnTypeName;
            this.paramCount = paramCount;
        }
    }

    /**
     * Runs both pilot queries against the base APK at {@code apkPath}. The caller owns the
     * session: this method creates exactly one bridge and closes it before returning.
     */
    static QueryResult[] scanBaseApk(String apkPath) {
        QueryResult ad = new QueryResult(0, "not run");
        QueryResult feed = new QueryResult(0, "not run");
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            ad = query(bridge, DexKitZinstantFingerprint.ANCHOR_AD_BIND);
            feed = query(bridge, DexKitZinstantFingerprint.ANCHOR_FEED_BIND);
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            ad = new QueryResult(0, error);
            feed = new QueryResult(0, error);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return new QueryResult[]{ad, feed};
    }

    private static QueryResult query(org.luckypray.dexkit.DexKitBridge bridge, String anchor) {
        try {
            org.luckypray.dexkit.result.MethodDataList found = bridge.findMethod(
                    org.luckypray.dexkit.query.FindMethod.create().matcher(
                            org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                                    .declaredClass(org.luckypray.dexkit.query.matchers
                                            .ClassMatcher.create().className(
                                                    DexKitZinstantFingerprint
                                                            .expectedOwner(anchor)))
                                    .returnType(DexKitZinstantFingerprint.RETURN_VOID)
                                    .paramCount(DexKitZinstantFingerprint
                                            .expectedParamCount(anchor))));
            QueryResult result = new QueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.MethodData method : found) {
                result.hits.add(new RawHit(method.getClassName(), method.getName(),
                        method.getReturnTypeName(), method.getParamCount()));
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new QueryResult(0, error);
        }
    }
}
