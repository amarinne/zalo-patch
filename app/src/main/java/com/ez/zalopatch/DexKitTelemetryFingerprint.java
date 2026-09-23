package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared DexKit fingerprint definition for the telemetry analytics-DAO family.
 *
 * <p>The Room generated database {@code com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl}
 * is name-stable. Its four zero-arg DAO accessors rotate names per release, so identity
 * comes from a two-hop semantic chain instead of remembered names:
 *
 * <ol>
 *   <li>Accessor candidates: zero-arg methods on the Impl whose return type is
 *       non-void, non-primitive, and outside {@code java./android./androidx./kotlin.}
 *       (mirrors the hook's {@code looksLikeDaoAccessor}).</li>
 *   <li>Table users: methods whose code loads {@code FROM <table>} for the stable
 *       Room table names {@code screens}, {@code views}, {@code events},
 *       {@code sessions} (verified in dex: each table's SELECTs live in exactly
 *       one DAO impl).</li>
 *   <li>Label mapping: the accessor whose return type is used by a table's methods
 *       is that table's accessor. Exactly one accessor per table, and four distinct
 *       accessors overall, or the family stays unavailable.</li>
 * </ol>
 *
 * <p>Dependency-free: hits arrive as plain shapes; the table linkage discriminates.
 */
public final class DexKitTelemetryFingerprint {
    public static final String ANCHOR_EVENT_ACCESSOR =
            "symbols.telemetry.analytics_event_accessor";
    public static final String ANCHOR_SCREEN_ACCESSOR =
            "symbols.telemetry.analytics_screen_accessor";
    public static final String ANCHOR_SESSION_ACCESSOR =
            "symbols.telemetry.analytics_session_accessor";
    public static final String ANCHOR_VIEW_ACCESSOR =
            "symbols.telemetry.analytics_view_accessor";

    public static final String QUERY_ACCESSORS = "telemetry.dao_accessors";
    public static final String QUERY_TABLE_PREFIX = "telemetry.table.";

    public static final String OWNER_DB_IMPL =
            "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl";

    private DexKitTelemetryFingerprint() {
    }

    /** One accessor-shaped method hit. */
    public static final class AccessorHit {
        public final String owner;
        public final String name;
        public final String returnType;

        public AccessorHit(String owner, String name, String returnType) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
        }
    }

    /** Table label to stable table name, in fixed order. */
    public static Map<String, String> labelTables() {
        LinkedHashMap<String, String> tables = new LinkedHashMap<>();
        tables.put("event", "events");
        tables.put("screen", "screens");
        tables.put("session", "sessions");
        tables.put("view", "views");
        return tables;
    }

    /** Anchor path for one table label. */
    public static String anchorForLabel(String label) {
        if ("event".equals(label)) {
            return ANCHOR_EVENT_ACCESSOR;
        }
        if ("screen".equals(label)) {
            return ANCHOR_SCREEN_ACCESSOR;
        }
        if ("session".equals(label)) {
            return ANCHOR_SESSION_ACCESSOR;
        }
        if ("view".equals(label)) {
            return ANCHOR_VIEW_ACCESSOR;
        }
        return "";
    }

    /** True when the return type can be a DAO type (mirrors looksLikeDaoAccessor). */
    public static boolean isDaoReturnType(String returnType) {
        String normalized = normalize(returnType);
        if (normalized.isEmpty() || "void".equals(normalized)) {
            return false;
        }
        if (normalized.length() == 1 || normalized.contains("(")) {
            return false;
        }
        switch (normalized) {
            case "boolean":
            case "byte":
            case "char":
            case "short":
            case "int":
            case "long":
            case "float":
            case "double":
                return false;
            default:
                break;
        }
        return !normalized.startsWith("java.")
                && !normalized.startsWith("android.")
                && !normalized.startsWith("androidx.")
                && !normalized.startsWith("kotlin.");
    }

    /** Normalizes JVM descriptors to dotted form; readable names pass through. */
    public static String normalize(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        if (type.charAt(0) == 'L' && type.endsWith(";")) {
            return type.substring(1, type.length() - 1).replace('/', '.');
        }
        if (type.length() == 1) {
            switch (type.charAt(0)) {
                case 'V': return "void";
                case 'Z': return "boolean";
                case 'B': return "byte";
                case 'C': return "char";
                case 'S': return "short";
                case 'I': return "int";
                case 'J': return "long";
                case 'F': return "float";
                case 'D': return "double";
                default: break;
            }
        }
        return type;
    }

    /**
     * Resolves all four accessors to bare method names keyed by anchor path.
     * Empty map unless every label maps to exactly one accessor and all four
     * accessors are distinct (family coherence).
     *
     * <p>Values are bare names (not {@code owner#name}): METHOD anchors serialize
     * only bare identifiers, and hook/preflight consumers call
     * {@code getDeclaredMethod} on the stable owner class.
     */
    public static Map<String, String> evaluate(List<AccessorHit> accessors,
                                               Map<String, Set<String>> tableUsers) {
        Map<String, String> diagnosis = diagnose(accessors, tableUsers);
        Map<String, String> resolved = new LinkedHashMap<>();
        Set<String> symbols = new LinkedHashSet<>();
        for (Map.Entry<String, String> labelTable : labelTables().entrySet()) {
            String status = diagnosis.get(labelTable.getKey());
            if (status == null || !status.startsWith("resolved:")) {
                return new LinkedHashMap<>();
            }
            String symbol = status.substring("resolved:".length());
            int hash = symbol.lastIndexOf('#');
            String bare = hash >= 0 ? symbol.substring(hash + 1) : symbol;
            if (bare.isEmpty() || !symbols.add(bare)) {
                return new LinkedHashMap<>();
            }
            resolved.put(anchorForLabel(labelTable.getKey()), bare);
        }
        return resolved;
    }

    /**
     * Per-label diagnosis for scan reporting: {@code resolved:<owner#name>},
     * {@code missing_table}, {@code no_candidates}, {@code ambiguous}, or
     * {@code duplicate} (all labels resolved but two share one accessor).
     */
    public static Map<String, String> diagnose(List<AccessorHit> accessors,
                                              Map<String, Set<String>> tableUsers) {        Map<String, String> diagnosis = new LinkedHashMap<>();
        if (accessors == null || tableUsers == null) {
            for (String label : labelTables().keySet()) {
                diagnosis.put(label, "no_candidates");
            }
            return diagnosis;
        }
        Map<String, String> winners = new LinkedHashMap<>();
        for (Map.Entry<String, String> labelTable : labelTables().entrySet()) {
            Set<String> users = tableUsers.get(labelTable.getValue());
            if (users == null) {
                diagnosis.put(labelTable.getKey(), "missing_table");
                continue;
            }
            String match = null;
            boolean ambiguous = false;
            for (AccessorHit accessor : accessors) {
                if (accessor == null || !OWNER_DB_IMPL.equals(accessor.owner)) {
                    continue;
                }
                if (!isDaoReturnType(accessor.returnType)) {
                    continue;
                }
                if (users.contains(normalize(accessor.returnType))) {
                    if (match != null) {
                        ambiguous = true;
                        break;
                    }
                    match = accessor.owner + "#" + accessor.name;
                }
            }
            if (ambiguous) {
                diagnosis.put(labelTable.getKey(), "ambiguous");
            } else if (match == null) {
                diagnosis.put(labelTable.getKey(), "no_candidates");
            } else {
                diagnosis.put(labelTable.getKey(), "resolved:" + match);
                winners.put(labelTable.getKey(), match);
            }
        }
        if (winners.size() == labelTables().size()
                && new LinkedHashSet<>(winners.values()).size() != winners.size()) {
            for (String label : winners.keySet()) {
                diagnosis.put(label, "duplicate");
            }
        }
        return diagnosis;
    }
}
