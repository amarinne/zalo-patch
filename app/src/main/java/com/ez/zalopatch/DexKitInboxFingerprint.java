package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared DexKit fingerprint definition for the inbox family.
 *
 * <p>Two anchors are resolved:
 *
 * <ul>
 *   <li>the category int field: the single {@code int} field on the stable Conversation
 *       class used by a method that also loads the number {@code 4}, reads
 *       {@code BIZ_BOX}, and logs {@code seen_oa_msg};</li>
 *   <li>the conversation uid field: the Conversation {@code String} field read by the
 *       no-arg {@code String} accessor of the row classes. Group detection is the stable
 *       {@code group_} UID convention, so this field alone decides it. No per-row
 *       group-flag method is resolved: that needed the DexKit invoke graph, which aborts
 *       the process for some methods, and it reintroduced a multi-way ambiguity the UID
 *       convention does not have.</li>
 * </ul>
 *
 * <p>Everything else stays runtime-derived by the feature and is never resolved here:
 * normal items are recognized structurally (a Conversation-typed field), because several
 * unrelated classes hold Conversations; the item-to-Conversation field is read per
 * observed class by reflection; the adapter comes from the MessagesView adapter field.
 * Row classes are located as the Conversation-holding subclasses of the adapter's
 * declared item type, which is a static class relation, not a runtime observation.
 *
 * <p>Dependency-free: hits arrive as plain shapes; uniqueness decides.
 */
public final class DexKitInboxFingerprint {
    public static final String ANCHOR_CATEGORY_FIELD = "symbols.inbox.category_int_field";

    public static final String ANCHOR_UID_FIELD = "symbols.inbox.conversation_uid_field";

    public static final String QUERY_CATEGORY_USERS = "inbox.category_users";

    public static final String QUERY_ROW_UID_METHODS = "inbox.row_uid_methods";

    public static final String CONVERSATION_CLASS =
            "com.zing.zalo.data.chat.model.tabmessage.Conversation";

    public static final String MESSAGES_VIEW_CLASS =
            "com.zing.zalo.ui.maintab.msg.MessagesView";

    public static final String RECYCLER_ADAPTER_CLASS =
            "androidx.recyclerview.widget.RecyclerView$Adapter";

    private DexKitInboxFingerprint() {
    }

    /** One method hit with used-field linkage. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final List<String> usedFields;

        public MethodHit(String owner, String name, List<String> usedFields) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.usedFields = usedFields == null
                    ? new ArrayList<String>() : new ArrayList<>(usedFields);
        }
    }

    /** Resolution outcome: the category field plus a machine-readable status. */
    public static final class Resolution {
        public final String field;
        public final String status;

        Resolution(String field, String status) {
            this.field = field == null ? "" : field;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /** One row-class accessor hit with used-field linkage. */
    public static final class RowMethodHit {
        public final String name;
        public final String returnType;
        public final List<String> usedFields;

        public RowMethodHit(String name, String returnType, List<String> usedFields) {
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.usedFields = usedFields == null
                    ? new ArrayList<String>() : new ArrayList<>(usedFields);
        }
    }

    /** Resolution outcome: the uid accessor plus the Conversation uid field. */
    public static final class RowResolution {
        public final String uidMethod;
        public final String uidField;
        public final String status;

        RowResolution(String uidMethod, String uidField, String status) {
            this.uidMethod = uidMethod == null ? "" : uidMethod;
            this.uidField = uidField == null ? "" : uidField;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Resolves the category field: the single Conversation-owned int field used by
     * semantically anchored OA methods. Anything but exactly one stays unavailable. No field-name
     * preference: ambiguity must fail closed rather than be resolved by a historical
     * obfuscated letter (Decision 18).
     *
     * @param numberUsers methods loading 4, BIZ_BOX, and seen_oa_msg, with used fields
     * @param conversationIntFields int field names on the Conversation class
     */
    public static Resolution evaluate(List<MethodHit> numberUsers,
                                      List<String> conversationIntFields) {
        if (numberUsers == null) {
            return new Resolution("", "no_category_users");
        }
        Set<String> categoryFields = new LinkedHashSet<>();
        for (MethodHit hit : numberUsers) {
            if (hit == null || hit.usedFields == null) {
                continue;
            }
            for (String used : hit.usedFields) {
                String field = fieldName(used);
                String owner = fieldOwner(used);
                if (field.isEmpty() || !CONVERSATION_CLASS.equals(owner)) {
                    continue;
                }
                if (conversationIntFields != null && !conversationIntFields.contains(field)) {
                    continue;
                }
                categoryFields.add(field);
            }
        }
        if (categoryFields.size() == 1) {
            return new Resolution(categoryFields.iterator().next(), "resolved");
        }
        if (categoryFields.isEmpty()) {
            return new Resolution("", "no_category_field");
        }
        return new Resolution("", "ambiguous_category_field");
    }

    /**
     * Resolves the category field from per-field narrow-query match counts: methods
     * that read one Conversation int field, load {@code 4}, read {@code BIZ_BOX}, and
     * log {@code seen_oa_msg}. Exactly one
     * matching field resolves; zero or several stay unavailable. A field-name
     * preference is deliberately absent: an ambiguous match must not be resolved by an
     * old obfuscated letter, which can silently classify the wrong rows on a later
     * release (Decision 18 ambiguity-fails-closed).
     */
    public static Resolution resolveFieldMatches(Map<String, Integer> fieldMatchCounts,
                                                 List<String> conversationIntFields) {
        if (fieldMatchCounts == null) {
            return new Resolution("", "no_category_users");
        }
        List<String> matched = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : fieldMatchCounts.entrySet()) {
            if (entry == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            if (conversationIntFields != null && conversationIntFields.contains(entry.getKey())) {
                matched.add(entry.getKey());
            }
        }
        if (matched.size() == 1) {
            return new Resolution(matched.get(0), "resolved");
        }
        if (matched.isEmpty()) {
            return new Resolution("", "no_category_field");
        }
        return new Resolution("", "ambiguous_category_field");
    }

    /**
     * Resolves the Conversation uid field from the row classes' no-arg String accessors.
     *
     * <p>Each accessor that reads exactly one Conversation String field contributes that
     * field. Exactly one distinct field across all accessors resolves; zero or several stay
     * unavailable, and a historical obfuscated letter must not break the tie (Decision 18).
     * The accessor name is reported for evidence only: the feature reads the field, so a
     * renamed accessor cannot misclassify rows.
     *
     * <p>Row classes are the Conversation-holding subclasses of the adapter's declared item
     * type, so the box-row siblings whose accessors return literal sentinels simply
     * contribute nothing.
     *
     * @param hits accessor hits scoped to those row classes
     * @param conversationStringFields String field names on the Conversation class
     */
    public static RowResolution resolveUidField(List<RowMethodHit> hits,
                                                List<String> conversationStringFields) {
        if (hits == null) {
            return new RowResolution("", "", "no_row_accessors");
        }
        Set<String> fields = new LinkedHashSet<>();
        String method = "";
        for (RowMethodHit hit : hits) {
            if (hit == null || hit.name.isEmpty()
                    || !"java.lang.String".equals(hit.returnType)) {
                continue;
            }
            Set<String> hitFields = new LinkedHashSet<>();
            for (String used : hit.usedFields) {
                String field = fieldName(used);
                if (field.isEmpty() || !CONVERSATION_CLASS.equals(fieldOwner(used))) {
                    continue;
                }
                if (conversationStringFields != null
                        && !conversationStringFields.contains(field)) {
                    continue;
                }
                hitFields.add(field);
            }
            if (hitFields.size() != 1) {
                continue;
            }
            fields.add(hitFields.iterator().next());
            if (method.isEmpty()) {
                method = hit.name;
            }
        }
        if (fields.size() == 1) {
            return new RowResolution(method, fields.iterator().next(), "resolved");
        }
        if (fields.isEmpty()) {
            return new RowResolution("", "", "no_uid_field");
        }
        return new RowResolution("", "", "ambiguous_uid_field");
    }

    static String fieldName(String qualified) {
        if (qualified == null) {
            return "";
        }
        int hash = qualified.indexOf('#');
        return hash < 0 ? qualified : qualified.substring(hash + 1);
    }

    static String fieldOwner(String qualified) {
        if (qualified == null) {
            return "";
        }
        int hash = qualified.indexOf('#');
        return hash <= 0 ? "" : qualified.substring(0, hash);
    }
}
