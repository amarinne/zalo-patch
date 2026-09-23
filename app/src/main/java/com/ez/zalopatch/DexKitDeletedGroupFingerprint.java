package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * DexKit fingerprint for the deleted-group store, anchored on the table literal rather
 * than on a remembered class name.
 *
 * <p>The store owns the {@code tbl_deleted_group_info} schema string and is the only
 * table user shaped as a singleton: a static field of its own type plus a
 * {@code (String) -> boolean} membership check. On 260901903 that resolves to
 * {@code eb0.c} (singleton {@code a}, check {@code b}) — the same letters as the
 * 260802903 profile, only the class moved — while the other table user is a database
 * helper with no membership predicate. Dependency-free: hits arrive as plain shapes and
 * live shape validation decides.
 */
public final class DexKitDeletedGroupFingerprint {
    public static final String QUERY_TABLE_OWNERS = "inbox.deleted_group_owners";

    public static final String TABLE = "tbl_deleted_group_info";

    public static final String ANCHOR_CLASS = "symbols.inbox.deleted_group_repository_class";

    public static final String ANCHOR_FIELD = "symbols.inbox.deleted_group_repository_field";

    public static final String ANCHOR_CHECK = "symbols.inbox.deleted_group_check_method";

    private DexKitDeletedGroupFingerprint() {
    }

    /** One class that uses the table, with the shapes read live from the loader. */
    public static final class Candidate {
        public final String className;
        public final String singletonField;
        public final String checkMethod;

        public Candidate(String className, String singletonField, String checkMethod) {
            this.className = className == null ? "" : className;
            this.singletonField = singletonField == null ? "" : singletonField;
            this.checkMethod = checkMethod == null ? "" : checkMethod;
        }
    }

    /** Resolution outcome: the store class plus its singleton field and check method. */
    public static final class Resolution {
        public final String className;
        public final String singletonField;
        public final String checkMethod;
        public final String status;

        Resolution(String className, String singletonField, String checkMethod, String status) {
            this.className = className == null ? "" : className;
            this.singletonField = singletonField == null ? "" : singletonField;
            this.checkMethod = checkMethod == null ? "" : checkMethod;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Picks the unique table user that is the store. Zero or several matching
     * candidates stay unavailable: a second singleton-shaped table user would mean the
     * shape no longer identifies the store.
     */
    public static Resolution evaluate(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution("", "", "", "no_table_owner");
        }
        List<Candidate> matching = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.className.isEmpty()) {
                continue;
            }
            if (candidate.singletonField.isEmpty() || candidate.checkMethod.isEmpty()) {
                continue;
            }
            matching.add(candidate);
        }
        if (matching.isEmpty()) {
            return new Resolution("", "", "", "no_membership_store");
        }
        if (matching.size() > 1) {
            return new Resolution("", "", "", "ambiguous_membership_store");
        }
        Candidate only = matching.get(0);
        return new Resolution(only.className, only.singletonField, only.checkMethod, "resolved");
    }
}
