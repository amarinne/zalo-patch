package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared DexKit fingerprint definition for the passcode-grace and scheduled-backup
 * families.
 *
 * <p>Both families read through two static config-reader shapes that share one
 * owner class in every mapped profile. Neither owner nor method name is stable, so
 * identity comes from string-anchored caller linkage instead of remembered names:
 *
 * <ul>
 *   <li>Passcode reader: the unique {@code static (int, String, boolean) -> int}
 *       invoked by callers that load the const string
 *       {@code SaveActiveTimePasscodeSetting}.</li>
 *   <li>Passcode setter: the unique {@code (int) -> void} invoked by callers that
 *       load the same key. Same-caller pairing with the reader is not required:
 *       the setter is preflight-only (never hooked), so hook safety rests on the
 *       reader's uniqueness plus its key binding plus live preflight. The key
 *       binding is the semantic link; callers split reads and writes across
 *       methods on the observed artifact.</li>
 *   <li>Backup reader: the unique {@code static (long, boolean, String) -> long}
 *       invoked by callers that load {@code SERVER_CONFIG_SYNC_MESSAGE_INTERVAL_}.</li>
 * </ul>
 *
 * <p>Dependency-free: callers and callees arrive as plain hits; uniqueness does the
 * discrimination. Zero or multiple qualifying callees stay unavailable through
 * {@link FingerprintResolver}, whose winner-takes-all margin rule is exactly the
 * ambiguity gate.
 */
public final class DexKitPasscodeFingerprint {
    public static final String ANCHOR_READER_CLASS =
            "symbols.passcode.prefs_int_reader_class";
    public static final String ANCHOR_READER_METHOD =
            "symbols.passcode.prefs_int_reader_method";
    public static final String ANCHOR_SETTER_CLASS =
            "symbols.passcode.active_time_setter_class";
    public static final String ANCHOR_SETTER_METHOD =
            "symbols.passcode.active_time_setter_method";
    public static final String ANCHOR_BACKUP_CLASS =
            "symbols.backup.interval_reader_class";
    public static final String ANCHOR_BACKUP_METHOD =
            "symbols.backup.interval_reader_method";

    public static final String QUERY_PASSCODE_CALLERS = "passcode.key_users";
    public static final String QUERY_BACKUP_CALLERS = "backup.key_users";

    public static final String PASSCODE_KEY = "SaveActiveTimePasscodeSetting";
    public static final String BACKUP_KEY_PREFIX = "SERVER_CONFIG_SYNC_MESSAGE_INTERVAL_";

    private DexKitPasscodeFingerprint() {
    }

    /** One invoked callee: owner, name, shape, and staticness. */
    public static final class Callee {
        public final String owner;
        public final String name;
        public final String returnType;
        public final List<String> paramTypes;
        public final boolean isStatic;

        public Callee(String owner, String name, String returnType,
                      List<String> paramTypes, boolean isStatic) {
            this.owner = owner == null ? "" : owner;
            this.name = name == null ? "" : name;
            this.returnType = returnType == null ? "" : returnType;
            this.paramTypes = paramTypes == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypes);
            this.isStatic = isStatic;
        }

        String identity() {
            return owner + "#" + name;
        }
    }

    /** One caller that loads the anchor string, with its invoked callees. */
    public static final class CallerHit {
        public final String callerOwner;
        public final String callerName;
        public final List<Callee> invoked;

        public CallerHit(String callerOwner, String callerName, List<Callee> invoked) {
            this.callerOwner = callerOwner == null ? "" : callerOwner;
            this.callerName = callerName == null ? "" : callerName;
            this.invoked = invoked == null
                    ? new ArrayList<Callee>() : new ArrayList<>(invoked);
        }
    }

    private static boolean isReaderShape(Callee callee) {
        return callee.isStatic && "int".equals(callee.returnType)
                && callee.paramTypes.size() == 3
                && "int".equals(callee.paramTypes.get(0))
                && "java.lang.String".equals(callee.paramTypes.get(1))
                && "boolean".equals(callee.paramTypes.get(2));
    }

    private static boolean isSetterShape(Callee callee) {
        return "void".equals(callee.returnType)
                && callee.paramTypes.size() == 1
                && "int".equals(callee.paramTypes.get(0));
    }

    private static boolean isBackupShape(Callee callee) {
        return callee.isStatic && "long".equals(callee.returnType)
                && callee.paramTypes.size() == 3
                && "long".equals(callee.paramTypes.get(0))
                && "boolean".equals(callee.paramTypes.get(1))
                && "java.lang.String".equals(callee.paramTypes.get(2));
    }

    private static Map<String, Callee> distinct(List<CallerHit> callers,
                                                java.util.function.Predicate<Callee> shape) {
        Map<String, Callee> unique = new LinkedHashMap<>();
        if (callers == null) {
            return unique;
        }
        for (CallerHit caller : callers) {
            if (caller == null) {
                continue;
            }
            for (Callee callee : caller.invoked) {
                if (callee != null && shape.test(callee)
                        && !unique.containsKey(callee.identity())) {
                    unique.put(callee.identity(), callee);
                }
            }
        }
        return unique;
    }

    /**
     * Resolves the passcode reader to an {@code owner#name} symbol: exactly one
     * reader-shaped callee invoked by key-loading callers.
     */
    public static FingerprintResolver.Resolution evaluateReader(List<CallerHit> callers,
            String expectedApkSha256, String actualApkSha256, boolean retainedVersion) {
        Map<String, Callee> readers = distinct(callers, DexKitPasscodeFingerprint::isReaderShape);
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        for (String identity : readers.keySet()) {
            boolean mandatory = readers.size() == 1;
            candidates.add(new FingerprintResolver.Candidate(
                    identity, true, mandatory, true, true, true,
                    retainedVersion && mandatory, 0));
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_READER_METHOD, false, candidates);
    }

    /**
     * Resolves the passcode setter to an {@code owner#name} symbol: the single
     * setter-shaped callee sharing a key-loading caller with the resolved reader.
     * The reader carries the hook safety (unique plus key-bound); the setter is
     * preflight-only, so sharing the reader's caller is the tightest available
     * semantic link. Zero or multiple sharing setters stay unavailable.
     */
    public static FingerprintResolver.Resolution evaluateSetter(List<CallerHit> callers,
            String readerIdentity, String expectedApkSha256, String actualApkSha256,
            boolean retainedVersion) {
        Map<String, Callee> setters = distinct(callers, DexKitPasscodeFingerprint::isSetterShape);
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        for (String identity : setters.keySet()) {
            boolean mandatory = uniquePairing(callers, readerIdentity, identity);
            candidates.add(new FingerprintResolver.Candidate(
                    identity, true, mandatory, true, true, true,
                    retainedVersion && mandatory, 0));
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_SETTER_METHOD, false, candidates);
    }

    /**
     * True when the candidate is the only setter-shaped callee sharing a key-loading
     * caller with the resolved reader.
     */
    private static boolean uniquePairing(List<CallerHit> callers, String reader,
                                         String setter) {
        if (callers == null || reader == null || reader.isEmpty()
                || setter == null || setter.isEmpty()) {
            return false;
        }
        java.util.LinkedHashSet<String> sharing = new java.util.LinkedHashSet<>();
        for (CallerHit caller : callers) {
            if (caller == null) {
                continue;
            }
            boolean hasReader = false;
            for (Callee callee : caller.invoked) {
                if (callee != null && reader.equals(callee.identity())) {
                    hasReader = true;
                    break;
                }
            }
            if (!hasReader) {
                continue;
            }
            for (Callee callee : caller.invoked) {
                if (callee != null && isSetterShape(callee)) {
                    sharing.add(callee.identity());
                }
            }
        }
        return sharing.size() == 1 && sharing.contains(setter);
    }

    /**
     * Resolves the backup reader to an {@code owner#name} symbol: exactly one
     * backup-shaped callee invoked by prefix-key callers.
     */
    public static FingerprintResolver.Resolution evaluateBackup(List<CallerHit> callers,
            String expectedApkSha256, String actualApkSha256, boolean retainedVersion) {
        Map<String, Callee> backups = distinct(callers, DexKitPasscodeFingerprint::isBackupShape);
        List<FingerprintResolver.Candidate> candidates = new ArrayList<>();
        for (String identity : backups.keySet()) {
            boolean mandatory = backups.size() == 1;
            candidates.add(new FingerprintResolver.Candidate(
                    identity, true, mandatory, true, true, true,
                    retainedVersion && mandatory, 0));
        }
        return FingerprintResolver.resolve(expectedApkSha256, actualApkSha256,
                ANCHOR_BACKUP_METHOD, false, candidates);
    }

    /** Splits an {@code owner#name} resolution symbol into owner and name. */
    public static String[] splitIdentity(String identity) {
        if (identity == null) {
            return new String[]{"", ""};
        }
        int separator = identity.indexOf('#');
        if (separator <= 0) {
            return new String[]{"", ""};
        }
        return new String[]{identity.substring(0, separator), identity.substring(separator + 1)};
    }
}
