package com.ez.zalopatch;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves the shared integer config reader from the two sponsored-placement key sites. */
public final class DexKitRemoteConfigFingerprint {
    public static final String ANCHOR_READER_CLASS =
            "symbols.remote_config.int_reader_class";
    public static final String ANCHOR_READER_METHOD =
            "symbols.remote_config.int_reader_method";

    public static final String QUERY_STORY_CALLERS = "remote_config.story.key_users";
    public static final String QUERY_COMMUNITY_CALLERS = "remote_config.community.key_users";

    public static final String STORY_KEY = "social@story@story_ads@enable";
    public static final String COMMUNITY_KEY = "community.community_ads.enable";

    private DexKitRemoteConfigFingerprint() {
    }

    /** One semantic result; no result is usable unless both key sites share one reader. */
    public static final class Resolution {
        public final String symbol;
        public final String status;

        Resolution(String symbol, String status) {
            this.symbol = symbol == null ? "" : symbol;
            this.status = status == null ? "unavailable" : status;
        }
    }

    /** One invoked callee's exact parsed Java-like shape. */
    public static final class Callee {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> paramTypes;
        public final boolean isStatic;

        public Callee(String owner, String name, String returnType, List<String> paramTypes,
                      boolean isStatic) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.paramTypes = paramTypes == null
                    ? java.util.Collections.<String>emptyList()
                    : new java.util.ArrayList<>(paramTypes);
            this.isStatic = isStatic;
        }

        String identity() {
            return owner + "#" + name;
        }
    }

    /** One method that contains an exact config key and the callees it invokes. */
    public static final class CallerHit {
        public final String owner;
        public final String name;
        public final List<Callee> invoked;

        public CallerHit(String owner, String name, List<Callee> invoked) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.invoked = invoked == null
                    ? java.util.Collections.<Callee>emptyList()
                    : new java.util.ArrayList<>(invoked);
        }
    }

    /**
     * Only the exact static {@code (String, int) -> int} callee shape participates in this
     * intersection.
     */
    public static Resolution evaluate(
            List<CallerHit> storyCallers, List<CallerHit> communityCallers) {
        Set<String> storyReaders = readers(storyCallers);
        Set<String> communityReaders = readers(communityCallers);
        storyReaders.retainAll(communityReaders);
        if (storyReaders.isEmpty()) {
            return new Resolution("", "no_shared_reader");
        }
        if (storyReaders.size() != 1) {
            return new Resolution("", "ambiguous_shared_readers");
        }
        return new Resolution(storyReaders.iterator().next(), "resolved");
    }

    /** Runtime guard shared with the hook: only the two exact host keys are in scope. */
    public static boolean isSponsoredPlacementKey(String key) {
        return STORY_KEY.equals(key) || COMMUNITY_KEY.equals(key);
    }

    public static String[] splitIdentity(String identity) {
        if (identity == null) {
            return new String[]{"", ""};
        }
        int separator = identity.indexOf('#');
        if (separator <= 0 || separator == identity.length() - 1) {
            return new String[]{"", ""};
        }
        return new String[]{identity.substring(0, separator), identity.substring(separator + 1)};
    }

    private static Set<String> readers(List<CallerHit> callers) {
        Set<String> result = null;
        if (callers == null) {
            return new LinkedHashSet<>();
        }
        for (CallerHit caller : callers) {
            if (caller == null) return new LinkedHashSet<>();
            Set<String> current = new LinkedHashSet<>();
            for (Callee callee : caller.invoked) {
                if (isReaderShape(callee)) {
                    current.add(callee.identity());
                }
            }
            if (result == null) result = current;
            else result.retainAll(current);
        }
        return result == null ? new LinkedHashSet<>() : result;
    }

    private static boolean isReaderShape(Callee callee) {
        return callee != null && !callee.owner.isEmpty() && !callee.name.isEmpty()
                && callee.isStatic && "int".equals(callee.returnType)
                && callee.paramTypes.size() == 2
                && "java.lang.String".equals(callee.paramTypes.get(0))
                && "int".equals(callee.paramTypes.get(1));
    }
}
