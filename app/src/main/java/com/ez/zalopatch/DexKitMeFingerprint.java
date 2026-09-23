package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared DexKit fingerprint definition for the Me TabMe-builder family.
 *
 * <p>The builder lives on the stable {@code TabMeView} and returns the settings
 * item list (one parameter, {@code ArrayList}/{@code List} return). Item classes,
 * fields, and integer ids are NOT resolved here: they are derived at runtime from
 * the builder's own products (observed classes plus item-shape duck-typing plus
 * title/description text markers), so a release that renames only the item layer
 * keeps working with zero mapping.
 *
 * <p>Dependency-free: candidate hits arrive as plain shapes; uniqueness decides.
 */
public final class DexKitMeFingerprint {
    public static final String ANCHOR_BUILDER = "symbols.me.current_builder_method";

    public static final String QUERY_BUILDERS = "me.builders";

    public static final String OWNER_TAB_ME = "com.zing.zalo.ui.maintab.me.TabMeView";

    private DexKitMeFingerprint() {
    }

    /** One candidate method hit. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final String returnType;
        public final int paramCount;

        public MethodHit(String owner, String name, String returnType, int paramCount) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.paramCount = paramCount;
        }
    }

    /** Resolution outcome: builder name plus a machine-readable status. */
    public static final class Resolution {
        public final String symbol;
        public final String status;

        Resolution(String symbol, String status) {
            this.symbol = symbol == null ? "" : symbol;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    private static boolean isBuilderShape(MethodHit hit) {
        if (hit == null || hit.paramCount != 1) {
            return false;
        }
        String returns = DexKitTelemetryFingerprint.normalize(hit.returnType);
        return "java.util.ArrayList".equals(returns) || "java.util.List".equals(returns);
    }

    /**
     * Resolves the builder to a method name: exactly one list-returning one-param
     * method on the stable owner. Zero or multiple stay unavailable.
     */
    public static Resolution evaluate(List<MethodHit> hits) {
        if (hits == null) {
            return new Resolution("", "no_candidates");
        }
        String match = null;
        boolean ambiguous = false;
        for (MethodHit hit : hits) {
            if (hit == null || !OWNER_TAB_ME.equals(hit.owner)) {
                continue;
            }
            if (!isBuilderShape(hit)) {
                continue;
            }
            if (match != null) {
                if (!match.equals(hit.name)) {
                    ambiguous = true;
                    break;
                }
                continue;
            }
            match = hit.name;
        }
        if (ambiguous) {
            return new Resolution("", "ambiguous");
        }
        if (match == null || match.isEmpty()) {
            return new Resolution("", "no_candidates");
        }
        return new Resolution(match, "resolved");
    }
}
