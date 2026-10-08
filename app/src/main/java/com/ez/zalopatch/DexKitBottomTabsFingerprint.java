package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared DexKit fingerprint definition for the bottom-tabs state family.
 *
 * <p>The tab state class re-obfuscates per release (verified: {@code tf1.w} on
 * {@code 260802903} denotes the tab state, on {@code 260901903} the same name denotes
 * an unrelated chat-context-menu runnable; the real state moved to {@code oh1.w}).
 * Identity therefore comes from structure plus live calibration instead of names:
 *
 * <ol>
 *   <li>Class shape: an {@code ArrayList} field, exactly 8 {@code int} fields,
 *       5-6 {@code boolean} fields, one {@code int[]} and one {@code boolean[]}
 *       field, a static zero-arg self-singleton, and a static
 *       {@code (enum)->int} icon resolver.</li>
 *   <li>Method linkage: every index/flag method is a trivial single-field getter;
 *       the used-field name maps each method to its field, and field order maps the
 *       field to its role. Field names are resolved per artifact, not pinned in Java.</li>
 *   <li>Rebuild/refresh: exactly 3 instance {@code ()->void} methods where one
 *       invokes a sibling void (refresh calls rebuild) and one invokes nothing.</li>
 *   <li>Live calibration: calling the 8 index getters on the singleton must not
 *       contradict the mapped roles (an index method returning a different role's
 *       value vetoes; out-of-range values abstain, e.g. hidden tabs).</li>
 *   <li>Enum: the icon resolver's parameter type must declare a {@code GROUP}
 *       constant (checked live; the tab names themselves are stable).</li>
 * </ol>
 *
 * <p>Dependency-free: member dumps and field orders arrive as plain data; mapping
 * decisions are JVM-tested. Live singleton calls stay in the resolver.
 */
public final class DexKitBottomTabsFingerprint {
    public static final String ANCHOR_STATE_CLASS = "symbols.bottom_tabs.state_class";
    public static final String ANCHOR_ENUM_CLASS = "symbols.bottom_tabs.enum_class";
    public static final String ANCHOR_SINGLETON = "symbols.bottom_tabs.singleton_method";
    public static final String ANCHOR_ICON = "symbols.bottom_tabs.icon_resolver_method";
    public static final String ANCHOR_REBUILD = "symbols.bottom_tabs.rebuild_method";
    public static final String ANCHOR_REFRESH = "symbols.bottom_tabs.refresh_method";
    public static final String ANCHOR_HIDE_DISCOVERY =
            "symbols.bottom_tabs.hide_discovery_method";
    public static final String ANCHOR_GROUP_FLAG = "symbols.bottom_tabs.group_flag_method";
    public static final String ANCHOR_LIST = "symbols.bottom_tabs.tabs_field";
    public static final String ANCHOR_SIZE = "symbols.bottom_tabs.size_method";

    public static final String QUERY_STATE_CANDIDATES = "bottomtabs.state_candidates";

    /** Schema roles in canonical tab order, then size. */
    public static final String[] INDEX_ROLES = {
            "message_index", "phonebook_index", "group_index", "discovery_index",
            "timeline_index", "more_index", "me_index", "size"};

    /** Anchor path per index role, in the same order as {@link #INDEX_ROLES}. */
    public static String anchorForIndexRole(String role) {
        return "size".equals(role)
                ? "symbols.bottom_tabs.size_method"
                : "symbols.bottom_tabs." + role + "_method";
    }

    /** Anchor path per index field role (message..size). */
    public static String fieldAnchorForIndexRole(String role) {
        String field = "size".equals(role) ? "size" : role.replace("_index", "");
        return "symbols.bottom_tabs." + field + "_index_field";
    }

    /** Anchor path per enabled-field role (group..me). */
    public static String fieldAnchorForEnabledRole(String role) {
        return "symbols.bottom_tabs." + role + "_enabled_field";
    }

    public static final String[] ENABLED_ROLES = {
            "group", "timeline", "discovery", "more", "me"};

    private DexKitBottomTabsFingerprint() {
    }

    /** One dumped method: shape plus linkage. */
    public static final class MethodHit {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> paramTypes;
        public final boolean isStatic;
        public final List<String> usedFields;
        public final List<String> invokedMethods;

        public MethodHit(String owner, String name, String returnType,
                         List<String> paramTypes, boolean isStatic,
                         List<String> usedFields, List<String> invokedMethods) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.paramTypes = paramTypes == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypes);
            this.isStatic = isStatic;
            this.usedFields = usedFields == null
                    ? new ArrayList<String>() : new ArrayList<>(usedFields);
            this.invokedMethods = invokedMethods == null
                    ? new ArrayList<String>() : new ArrayList<>(invokedMethods);
        }
    }

    /** Runtime field layout of one candidate class, in declaration order. */
    public static final class FieldLayout {
        public final List<String> intFields;
        public final List<String> boolFields;
        public final String intArrayField;
        public final String boolArrayField;
        public final boolean hasListField;
        public final String listField;

        public FieldLayout(List<String> intFields, List<String> boolFields,
                           String intArrayField, String boolArrayField,
                           boolean hasListField) {
            this.intFields = intFields == null
                    ? new ArrayList<String>() : new ArrayList<>(intFields);
            this.boolFields = boolFields == null
                    ? new ArrayList<String>() : new ArrayList<>(boolFields);
            this.intArrayField = intArrayField == null ? "" : intArrayField;
            this.boolArrayField = boolArrayField == null ? "" : boolArrayField;
            this.hasListField = hasListField;
            this.listField = "";
        }
        public FieldLayout(List<String> intFields, List<String> boolFields,
                           String intArrayField, String boolArrayField, String listField) {
            this.intFields = intFields == null ? new ArrayList<>() : new ArrayList<>(intFields);
            this.boolFields = boolFields == null ? new ArrayList<>() : new ArrayList<>(boolFields);
            this.intArrayField = intArrayField == null ? "" : intArrayField;
            this.boolArrayField = boolArrayField == null ? "" : boolArrayField;
            this.listField = listField == null ? "" : listField;
            this.hasListField = listField != null && !listField.isEmpty();
        }

    }

    /** Resolution outcome: role-anchored names plus a machine-readable status. */
    public static final class Resolution {
        public final Map<String, String> anchors;
        public final String enumClass;
        public final String status;

        Resolution(Map<String, String> anchors, String enumClass, String status) {
            this.anchors = anchors;
            this.enumClass = enumClass == null ? "" : enumClass;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    private static boolean isIntGetter(MethodHit hit) {
        return !isConstructor(hit.name) && !hit.isStatic && "int".equals(hit.returnType)
                && hit.paramTypes.isEmpty();
    }

    private static boolean isBoolGetter(MethodHit hit) {
        return !isConstructor(hit.name) && !hit.isStatic && "boolean".equals(hit.returnType)
                && hit.paramTypes.isEmpty();
    }

    private static boolean isVoidMethod(MethodHit hit) {
        return !isConstructor(hit.name) && !hit.isStatic && "void".equals(hit.returnType)
                && hit.paramTypes.isEmpty();
    }

    private static boolean isConstructor(String name) {
        return "<init>".equals(name) || "<clinit>".equals(name);
    }

    /** Single field a trivial getter reads, or "" when not exactly one. */
    static String singleUsedField(MethodHit hit) {
        if (hit.usedFields.size() != 1) {
            return "";
        }
        String qualified = hit.usedFields.get(0);
        int hash = qualified.indexOf('#');
        return hash < 0 ? qualified : qualified.substring(hash + 1);
    }

    /** Per-candidate shape counts for scan forensics: "8i/0b/3v/1s/1e". */
    public static Map<String, String> shapeSummary(Map<String, List<MethodHit>> dumps) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (dumps == null) {
            return summary;
        }
        for (Map.Entry<String, List<MethodHit>> candidate : dumps.entrySet()) {
            if (candidate.getKey() == null || candidate.getKey().isEmpty()) {
                continue;
            }
            int ints = 0;
            int bools = 0;
            int voids = 0;
            int selfSingletons = 0;
            int enumToInts = 0;
            if (candidate.getValue() != null) {
                for (MethodHit hit : candidate.getValue()) {
                    if (hit == null || !candidate.getKey().equals(hit.owner)) {
                        continue;
                    }
                    if (isIntGetter(hit)) {
                        ints++;
                    } else if (isBoolGetter(hit)) {
                        bools++;
                    } else if (isVoidMethod(hit)) {
                        voids++;
                    } else if (hit.isStatic && hit.paramTypes.isEmpty()
                            && candidate.getKey().equals(hit.returnType)) {
                        selfSingletons++;
                    } else if (hit.isStatic && "int".equals(hit.returnType)
                            && hit.paramTypes.size() == 1
                            && isEnumType(hit.paramTypes.get(0))) {
                        enumToInts++;
                    }
                }
            }
            summary.put(candidate.getKey(),
                    ints + "i/" + bools + "b/" + voids + "v/" + selfSingletons + "s/"
                            + enumToInts + "e");
        }
        return summary;
    }

    /**
     * Picks the state class from method dumps keyed by candidate class. Exactly one
     * candidate may carry the legacy getter shape or the compact October shape.
     * Both still require one static self-singleton and enum-to-int method; compact
     * candidates must subsequently prove the complete rebuild field/enum linkage.
     */
    public static String selectStateClass(Map<String, List<MethodHit>> dumps) {
        if (dumps == null) {
            return "";
        }
        String match = null;
        boolean ambiguous = false;
        for (Map.Entry<String, List<MethodHit>> candidate : dumps.entrySet()) {
            if (candidate.getKey() == null || candidate.getKey().isEmpty()) {
                continue;
            }
            if (hasFullShape(candidate.getKey(), candidate.getValue())) {
                if (match != null) {
                    ambiguous = true;
                    break;
                }
                match = candidate.getKey();
            }
        }
        return ambiguous ? "" : (match == null ? "" : match);
    }

    static boolean hasFullShape(String owner, List<MethodHit> hits) {
        if (hits == null) {
            return false;
        }
        int ints = 0;
        int voids = 0;
        int bools = 0;
        int selfSingletons = 0;
        int enumToInts = 0;
        for (MethodHit hit : hits) {
            if (hit == null || !owner.equals(hit.owner)) {
                continue;
            }
            if (isIntGetter(hit)) {
                ints++;
            } else if (isBoolGetter(hit)) {
                bools++;
            } else if (isVoidMethod(hit)) {
                voids++;
            } else if (hit.isStatic && hit.paramTypes.isEmpty()
                    && owner.equals(hit.returnType)) {
                selfSingletons++;
            } else if (hit.isStatic && "int".equals(hit.returnType)
                    && hit.paramTypes.size() == 1
                    && isEnumType(hit.paramTypes.get(0))) {
                enumToInts++;
            }
        }
        return selfSingletons == 1 && enumToInts == 1
                && ((ints == 8 && voids == 3) || (ints == 4 && bools == 1 && voids == 1));
    }

    private static boolean isEnumType(String type) {
        return type != null && !type.isEmpty() && !"void".equals(type)
                && !"boolean".equals(type) && !"int".equals(type) && !"long".equals(type)
                && !"java.lang.String".equals(type) && !"android.os.Bundle".equals(type)
                && type.contains(".");
    }

    /**
     * Resolves every tab anchor for a selected state class. The field layout is
     * structural: declaration order maps fields to roles, while live calibration
     * confirms the mapping before hooks are armed. Returns the enum class separately
     * for the live GROUP check in the resolver.
     */
    public static Resolution resolve(String stateClass, List<MethodHit> hits,
                                     FieldLayout layout) {
        Map<String, String> anchors = new LinkedHashMap<>();
        if (stateClass == null || stateClass.isEmpty() || hits == null || layout == null) {
            return new Resolution(anchors, "", "no_candidates");
        }
        if (!layoutGate(layout)) {
            return new Resolution(anchors, "", "field_shape_changed");
        }
        String singleton = null;
        String icon = null;
        String enumClass = "";
        for (MethodHit hit : hits) {
            if (hit == null || !stateClass.equals(hit.owner)) {
                continue;
            }
            if (hit.isStatic && hit.paramTypes.isEmpty() && stateClass.equals(hit.returnType)) {
                if (singleton != null) {
                    return new Resolution(anchors, "", "ambiguous_singleton");
                }
                singleton = hit.name;
            } else if (hit.isStatic && "int".equals(hit.returnType)
                    && hit.paramTypes.size() == 1 && isEnumType(hit.paramTypes.get(0))) {
                if (icon != null) {
                    return new Resolution(anchors, "", "ambiguous_icon_resolver");
                }
                icon = hit.name;
                enumClass = hit.paramTypes.get(0);
            }
        }
        if (singleton == null || singleton.isEmpty()) {
            return new Resolution(anchors, "", "no_singleton");
        }
        if (icon == null || icon.isEmpty() || enumClass.isEmpty()) {
            return new Resolution(anchors, "", "no_icon_resolver");
        }
        boolean compact = isCompact(hits);
        Map<String, String> voids = compact
                ? resolveCompactRebuild(stateClass, enumClass, hits, layout)
                : resolveVoids(stateClass, hits);
        if (voids == null) {
            return new Resolution(anchors, "", "void_pattern_changed");
        }
        Map<String, String> indexes = resolveIndexes(hits, layout, compact);
        if (indexes == null) {
            return new Resolution(anchors, "", "index_mapping_failed");
        }
        Map<String, String> flags = compact ? resolveCompactFlags(hits, layout) : resolveFlags(hits, layout);
        if (flags == null) {
            return new Resolution(anchors, "", "flag_mapping_failed");
        }
        anchors.put(ANCHOR_STATE_CLASS, stateClass);
        anchors.put(ANCHOR_ENUM_CLASS, enumClass);
        anchors.put(ANCHOR_SINGLETON, singleton);
        anchors.put(ANCHOR_ICON, icon);
        anchors.put(ANCHOR_REBUILD, voids.get("rebuild"));
        if (compact) {
            anchors.put(ANCHOR_LIST, layout.listField);
        } else {
            anchors.put(ANCHOR_REFRESH, voids.get("refresh"));
            anchors.put(ANCHOR_GROUP_FLAG, flags.get("group_flag"));
        }
        anchors.put(ANCHOR_HIDE_DISCOVERY, flags.get("hide_discovery"));
        for (int index = 0; index < INDEX_ROLES.length; index++) {
            String getter = indexes.get(INDEX_ROLES[index]);
            if (getter != null) {
                anchors.put(anchorForIndexRole(INDEX_ROLES[index]), getter);
            }
        }
        for (int index = 0; index < layout.intFields.size() && index < 8; index++) {
            anchors.put(fieldAnchorForIndexRole(INDEX_ROLES[index]), layout.intFields.get(index));
        }
        for (int index = 0; index < ENABLED_ROLES.length; index++) {
            anchors.put(fieldAnchorForEnabledRole(ENABLED_ROLES[index]),
                    layout.boolFields.get(index));
        }
        anchors.put("symbols.bottom_tabs.icons_field", layout.intArrayField);
        anchors.put("symbols.bottom_tabs.preloaded_field", layout.boolArrayField);
        return new Resolution(anchors, enumClass, "resolved");
    }

    static boolean layoutGate(FieldLayout layout) {
        if (layout.intFields.size() != 8 || layout.boolFields.size() < 5
                || layout.boolFields.size() > 6) {
            return false;
        }
        if (layout.intArrayField.isEmpty() || layout.boolArrayField.isEmpty()
                || !layout.hasListField) {
            return false;
        }
        return true;
    }

    /**
     * Maps rebuild/refresh among the 3 instance {@code ()->void} methods: refresh is
     * the one invoking a sibling void (it calls rebuild), rebuild is the invoked
     * sibling, the third invokes no state voids. Returns null on any other pattern.
     */
    static Map<String, String> resolveVoids(String stateClass, List<MethodHit> hits) {
        List<MethodHit> voids = new ArrayList<>();
        for (MethodHit hit : hits) {
            if (hit != null && stateClass.equals(hit.owner) && isVoidMethod(hit)) {
                voids.add(hit);
            }
        }
        if (voids.size() != 3) {
            return null;
        }
        Map<String, String> byName = new LinkedHashMap<>();
        for (MethodHit hit : voids) {
            byName.put(hit.name, hit.name);
        }
        String refresh = null;
        String rebuild = null;
        for (MethodHit hit : voids) {
            List<String> siblings = new ArrayList<>();
            for (String invoked : hit.invokedMethods) {
                int hash = invoked.indexOf('#');
                String invokedOwner = hash < 0 ? "" : invoked.substring(0, hash);
                String invokedName = hash < 0 ? invoked : invoked.substring(hash + 1);
                if (stateClass.equals(invokedOwner) && byName.containsKey(invokedName)
                        && !invokedName.equals(hit.name)) {
                    siblings.add(invokedName);
                }
            }
            if (siblings.size() == 1) {
                if (refresh != null) {
                    return null;
                }
                refresh = hit.name;
                rebuild = siblings.get(0);
            } else if (!siblings.isEmpty()) {
                return null;
            }
        }
        if (refresh == null || rebuild == null) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("rebuild", rebuild);
        resolved.put("refresh", refresh);
        return resolved;
    }

    /**
     * Maps the 8 index getters to roles through their single used int field and the
     * field declaration order. Every getter must read exactly one of the 8 int
     * fields, and all 8 fields must be covered exactly once.
     */
    static Map<String, String> resolveIndexes(List<MethodHit> hits, FieldLayout layout) {
        return resolveIndexes(hits, layout, false);
    }

    static Map<String, String> resolveIndexes(List<MethodHit> hits, FieldLayout layout,
                                               boolean compact) {
        Map<String, String> roles = new LinkedHashMap<>();
        boolean[] covered = new boolean[8];
        int count = 0;
        for (MethodHit hit : hits) {
            if (hit == null || !isIntGetter(hit)) {
                continue;
            }
            String field = singleUsedField(hit);
            int position = layout.intFields.indexOf(field);
            if (field.isEmpty() || position < 0 || position >= 8 || covered[position]) {
                return null;
            }
            covered[position] = true;
            roles.put(INDEX_ROLES[position], hit.name);
            count++;
        }
        if (count != (compact ? 4 : 8)) {
            return null;
        }
        if (compact && (!roles.containsKey("message_index")
                || !roles.containsKey("group_index") || !roles.containsKey("discovery_index")
                || !roles.containsKey("timeline_index"))) {
            return null;
        }
        return roles;
    }

    private static boolean isCompact(List<MethodHit> hits) {
        int voids = 0;
        for (MethodHit hit : hits) {
            if (hit != null && isVoidMethod(hit)) voids++;
        }
        return voids == 1;
    }

    // October removed refresh and four getters. Its only rebuild still touches the
    // complete state and all named enum values. Missing methods stay absent.
    private static Map<String, String> resolveCompactRebuild(String owner, String enumClass,
            List<MethodHit> hits, FieldLayout layout) {
        if (layout.listField == null || layout.listField.isEmpty()) return null;
        MethodHit rebuild = null;
        for (MethodHit hit : hits) {
            if (hit != null && owner.equals(hit.owner) && isVoidMethod(hit)) {
                if (rebuild != null) return null;
                rebuild = hit;
            }
        }
        if (rebuild == null) return null;
        List<String> required = new ArrayList<>(layout.intFields);
        required.addAll(layout.boolFields.subList(0, 5));
        required.add(layout.intArrayField);
        required.add(layout.boolArrayField);
        required.add(layout.listField);
        for (String field : required) {
            if (!rebuild.usedFields.contains(owner + "#" + field)) return null;
        }
        for (String tab : new String[]{"MESSAGE", "PHONEBOOK", "GROUP", "DISCOVERY",
                "TIMELINE", "MORE", "ME"}) {
            if (!rebuild.usedFields.contains(enumClass + "#" + tab)) return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("rebuild", rebuild.name);
        return result;
    }

    private static Map<String, String> resolveCompactFlags(List<MethodHit> hits,
                                                            FieldLayout layout) {
        String discovery = null;
        for (MethodHit hit : hits) {
            if (hit != null && isBoolGetter(hit)) {
                if (discovery != null || !singleUsedField(hit).equals(layout.boolFields.get(2))) {
                    return null;
                }
                discovery = hit.name;
            }
        }
        if (discovery == null) return null;
        Map<String, String> result = new LinkedHashMap<>();
        result.put("hide_discovery", discovery);
        return result;
    }

    /** Shared family completeness gate for overlay synthesis and cache admission. */
    public static boolean complete(Map<String, String> leaves) {
        if (leaves == null) return false;
        for (String key : new String[]{ANCHOR_STATE_CLASS, ANCHOR_ENUM_CLASS,
                ANCHOR_SINGLETON, ANCHOR_ICON, ANCHOR_REBUILD, ANCHOR_HIDE_DISCOVERY}) {
            if (!present(leaves, key)) return false;
        }
        for (String role : INDEX_ROLES) {
            if (!present(leaves, fieldAnchorForIndexRole(role))) return false;
        }
        for (String role : ENABLED_ROLES) {
            if (!present(leaves, fieldAnchorForEnabledRole(role))) return false;
        }
        if (!present(leaves, "symbols.bottom_tabs.icons_field")
                || !present(leaves, "symbols.bottom_tabs.preloaded_field")) return false;
        if (present(leaves, ANCHOR_LIST)) {
            if (present(leaves, ANCHOR_REFRESH) || present(leaves, ANCHOR_GROUP_FLAG)) return false;
            for (String role : INDEX_ROLES) {
                boolean retained = "message_index".equals(role) || "group_index".equals(role)
                        || "discovery_index".equals(role) || "timeline_index".equals(role);
                if (present(leaves, anchorForIndexRole(role)) != retained) return false;
            }
            return true;
        }
        if (!present(leaves, ANCHOR_REFRESH) || !present(leaves, ANCHOR_GROUP_FLAG)) return false;
        for (String role : INDEX_ROLES) {
            if (!present(leaves, anchorForIndexRole(role))) return false;
        }
        return true;
    }

    private static boolean present(Map<String, String> leaves, String key) {
        return leaves.get(key) != null && !leaves.get(key).isEmpty();
    }

    /**
     * Maps the two hooked flags: the getter reading bool field 0 (group) is the
     * group flag, the one reading bool field 2 (discovery) hides discovery. Exactly
     * one method per hooked field; other bool readers stay unhooked. Returns null
     * when either hooked field has zero or multiple readers.
     */
    static Map<String, String> resolveFlags(List<MethodHit> hits, FieldLayout layout) {
        String groupReader = null;
        String discoveryReader = null;
        for (MethodHit hit : hits) {
            if (hit == null || !isBoolGetter(hit)) {
                continue;
            }
            String field = singleUsedField(hit);
            if (field.isEmpty()) {
                continue;
            }
            if (field.equals(layout.boolFields.get(0))) {
                if (groupReader != null) {
                    return null;
                }
                groupReader = hit.name;
            } else if (layout.boolFields.size() > 2 && field.equals(layout.boolFields.get(2))) {
                if (discoveryReader != null) {
                    return null;
                }
                discoveryReader = hit.name;
            }
        }
        if (groupReader == null || groupReader.isEmpty()
                || discoveryReader == null || discoveryReader.isEmpty()) {
            return null;
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put("group_flag", groupReader);
        resolved.put("hide_discovery", discoveryReader);
        return resolved;
    }

    public static String calibrateState(List<String> tabs, Map<String, Integer> indexes,
                                        Map<String, Boolean> enabled) {
        String result = calibrateFields(tabs, indexes);
        if (!"full".equals(result)) return result;
        if (enabled == null) return "inconclusive";
        for (String role : ENABLED_ROLES) {
            Boolean value = enabled.get(role);
            if (value == null || value != tabs.contains(role.toUpperCase(java.util.Locale.ROOT))) {
                return "contradicted";
            }
        }
        return "full";
    }

    public static String calibrateFields(List<String> tabs, Map<String, Integer> indexes) {
        if (tabs == null || indexes == null || tabs.size() < 2) return "inconclusive";
        String[] names = {"MESSAGE", "PHONEBOOK", "GROUP", "DISCOVERY", "TIMELINE", "MORE", "ME"};
        java.util.HashSet<String> unique = new java.util.HashSet<>(tabs);
        if (unique.size() != tabs.size()) return "contradicted";
        for (int i = 0; i < names.length; i++) {
            Integer value = indexes.get(INDEX_ROLES[i]);
            if (value == null || value != tabs.indexOf(names[i])) return "contradicted";
        }
        Integer size = indexes.get("size");
        return size != null && size == tabs.size() ? "full" : "contradicted";
    }

    /**
     * Live calibration: index getters return live filtered positions (hidden tabs
     * read -1), so absolute values cannot confirm canonical roles. What filtering
     * cannot change is ORDER: visible values, sorted by value, must list roles in
     * canonical order, occupy exactly {@code 0..n-1}, and the size method must read
     * the visible count. Any other pattern vetoes ({@code contradicted}).
     * Uniform values abstain as {@code inconclusive} (uninitialized singleton:
     * distinct tabs cannot share one position).
     */
    public static String calibrate(Map<String, Integer> values, Map<String, String> roles) {
        if (values == null || roles == null) {
            return "inconclusive";
        }
        List<int[]> visible = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            String method = roles.get(INDEX_ROLES[index]);
            if (method == null || method.isEmpty() || !values.containsKey(method)) {
                continue;
            }
            int value = values.get(method);
            if (value < 0 || value > 6) {
                continue;
            }
            visible.add(new int[]{index, value});
        }
        if (visible.size() >= 2) {
            boolean uniform = true;
            for (int[] item : visible) {
                if (item[1] != visible.get(0)[1]) {
                    uniform = false;
                    break;
                }
            }
            if (uniform) {
                return "inconclusive";
            }
        }
        visible.sort((left, right) -> Integer.compare(left[1], right[1]));
        for (int position = 0; position < visible.size(); position++) {
            int[] item = visible.get(position);
            if (position > 0 && item[1] == visible.get(position - 1)[1]) {
                return "contradicted";
            }
            if (item[1] != position) {
                return "contradicted";
            }
            if (position > 0 && item[0] <= visible.get(position - 1)[0]) {
                return "contradicted";
            }
        }
        String sizeMethod = roles.get("size");
        if (sizeMethod != null && !sizeMethod.isEmpty() && values.containsKey(sizeMethod)
                && values.get(sizeMethod) != visible.size()) {
            return "contradicted";
        }
        return visible.size() >= 2 ? "full" : "inconclusive";
    }
}
