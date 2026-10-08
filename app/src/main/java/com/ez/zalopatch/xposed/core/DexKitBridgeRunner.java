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

    /**
     * Cap on hits that receive per-method detail extraction. A broad query (for example a
     * bare number literal) can match tens of thousands of methods; materializing used-field
     * lists for all of them exhausts the Zalo heap. Above the cap, hits stay plain and
     * callers stay honestly unavailable instead of allocating.
     */
    static final int MAX_DETAIL_HITS = 512;

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
        final java.util.List<String> paramTypeNames;
        final boolean isStatic;
        final java.util.List<RawHit> invoked;
        final java.util.List<String> usedFields;

        RawHit(String className, String methodName, String returnTypeName, int paramCount,
               java.util.List<String> paramTypeNames, boolean isStatic) {
            this(className, methodName, returnTypeName, paramCount, paramTypeNames, isStatic,
                    new java.util.ArrayList<RawHit>());
        }

        RawHit(String className, String methodName, String returnTypeName, int paramCount,
               java.util.List<String> paramTypeNames, boolean isStatic,
               java.util.List<RawHit> invoked) {
            this(className, methodName, returnTypeName, paramCount, paramTypeNames, isStatic,
                    invoked, new java.util.ArrayList<String>());
        }

        RawHit(String className, String methodName, String returnTypeName, int paramCount,
               java.util.List<String> paramTypeNames, boolean isStatic,
               java.util.List<RawHit> invoked, java.util.List<String> usedFields) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.returnTypeName = returnTypeName == null ? "" : returnTypeName;
            this.paramCount = paramCount;
            this.paramTypeNames = paramTypeNames == null
                    ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(paramTypeNames);
            this.isStatic = isStatic;
            this.invoked = invoked == null
                    ? new java.util.ArrayList<RawHit>() : new java.util.ArrayList<>(invoked);
            this.usedFields = usedFields == null
                    ? new java.util.ArrayList<String>() : new java.util.ArrayList<>(usedFields);
        }
    }

    /** Plain string-usage query spec; one group per id, Contains match. */
    static final class StringSpec {
        final String id;
        final String value;
        final boolean withInvokes;

        StringSpec(String id, String value) {
            this(id, value, true);
        }

        StringSpec(String id, String value, boolean withInvokes) {
            this.id = id == null ? "" : id;
            this.value = value == null ? "" : value;
            this.withInvokes = withInvokes;
        }
    }
    /** Plain method-query spec; the bridge translates it to DexKit matchers. */
    static final class MethodSpec {
        final String id;
        final String ownerClass;
        final String returnType;
        final Integer paramCount;
        final boolean withDetails;
        final Integer usingNumber;
        /**
         * When {@link #withDetails} is set, whether callee invokes are also read.
         * DexKit's native {@code getInvokes} aborts the process (JNI {@code java_array == null})
         * for some methods, so callers that only need used fields must leave this false.
         */
        final boolean withInvokes;

        MethodSpec(String id, String ownerClass, String returnType, Integer paramCount) {
            this(id, ownerClass, returnType, paramCount, false, null, false);
        }

        MethodSpec(String id, String ownerClass, String returnType, Integer paramCount,
                   boolean withDetails) {
            this(id, ownerClass, returnType, paramCount, withDetails, null, withDetails);
        }

        MethodSpec(String id, String ownerClass, String returnType, Integer paramCount,
                   boolean withDetails, Integer usingNumber) {
            this(id, ownerClass, returnType, paramCount, withDetails, usingNumber, withDetails);
        }

        MethodSpec(String id, String ownerClass, String returnType, Integer paramCount,
                   boolean withDetails, Integer usingNumber, boolean withInvokes) {
            this.id = id == null ? "" : id;
            this.ownerClass = ownerClass;
            this.returnType = returnType;
            this.paramCount = paramCount;
            this.withDetails = withDetails;
            this.usingNumber = usingNumber;
            this.withInvokes = withInvokes;
        }
    }

    /**
     * Field-use query spec: methods that read a named field and load a number.
     * Narrow by construction, so result sets stay small and detail extraction is safe.
     */
    static final class FieldUseSpec {
        final String id;
        final String fieldOwner;
        final String fieldName;
        final Integer usingNumber;
        final String semanticFieldName;
        final String semanticString;

        FieldUseSpec(String id, String fieldOwner, String fieldName, Integer usingNumber) {
            this(id, fieldOwner, fieldName, usingNumber, null, null);
        }

        FieldUseSpec(String id, String fieldOwner, String fieldName, Integer usingNumber,
                     String semanticFieldName, String semanticString) {
            this.id = id == null ? "" : id;
            this.semanticFieldName = semanticFieldName;
            this.semanticString = semanticString;
            this.fieldOwner = fieldOwner;
            this.fieldName = fieldName;
            this.usingNumber = usingNumber;
        }
    }

    /**
     * Plain class-query spec; baked bottom-tab state candidates for now.
     */
    static final class ClassSpec {
        final String id;

        ClassSpec(String id) {
            this.id = id == null ? "" : id;
        }
    }

    /** Class names from one class query, plus the total match count. */
    static final class ClassQueryResult {
        final java.util.List<String> classNames = new java.util.ArrayList<>();
        final int matchCount;
        final String error;

        ClassQueryResult(int matchCount, String error) {
            this.matchCount = matchCount;
            this.error = error == null ? "" : error;
        }
    }

    /**
     * Runs both pilot queries against the base APK at {@code apkPath}. The caller owns the
     * session: this method creates exactly one bridge and closes it before returning.
     */
    static QueryResult[] scanBaseApk(String apkPath) {
        java.util.Map<String, QueryResult> results = scanMethods(apkPath,
                java.util.Arrays.asList(
                        new MethodSpec(DexKitZinstantFingerprint.ANCHOR_AD_BIND,
                                DexKitZinstantFingerprint.expectedOwner(
                                        DexKitZinstantFingerprint.ANCHOR_AD_BIND),
                                DexKitZinstantFingerprint.RETURN_VOID,
                                DexKitZinstantFingerprint.expectedParamCount(
                                        DexKitZinstantFingerprint.ANCHOR_AD_BIND)),
                        new MethodSpec(DexKitZinstantFingerprint.ANCHOR_FEED_BIND,
                                DexKitZinstantFingerprint.expectedOwner(
                                        DexKitZinstantFingerprint.ANCHOR_FEED_BIND),
                                DexKitZinstantFingerprint.RETURN_VOID,
                                DexKitZinstantFingerprint.expectedParamCount(
                                        DexKitZinstantFingerprint.ANCHOR_FEED_BIND))));
        QueryResult ad = results.get(DexKitZinstantFingerprint.ANCHOR_AD_BIND);
        QueryResult feed = results.get(DexKitZinstantFingerprint.ANCHOR_FEED_BIND);
        if (ad == null) {
            ad = new QueryResult(0, "not run");
        }
        if (feed == null) {
            feed = new QueryResult(0, "not run");
        }
        return new QueryResult[]{ad, feed};
    }

    /**
     * Runs a batch of method queries against the base APK in one bridge session,
     * keyed by spec id. Failures are per-spec: one bad spec never poisons the rest.
     */
    static java.util.Map<String, QueryResult> scanMethods(String apkPath,
                                                          java.util.List<MethodSpec> specs) {
        java.util.Map<String, QueryResult> results = new java.util.LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (MethodSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, query(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (MethodSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new QueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    /**
     * Runs field-use queries: methods that read a named field and load a number.
     * Result sets are narrow by construction, so no detail cap applies.
     */
    static java.util.Map<String, QueryResult> scanFieldUses(String apkPath,
                                                            java.util.List<FieldUseSpec> specs) {
        java.util.Map<String, QueryResult> results = new java.util.LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (FieldUseSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, queryFieldUse(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (FieldUseSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new QueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    private static QueryResult queryFieldUse(org.luckypray.dexkit.DexKitBridge bridge,
                                             FieldUseSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.MethodMatcher matcher =
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create();
            org.luckypray.dexkit.query.matchers.FieldMatcher fieldMatcher =
                    org.luckypray.dexkit.query.matchers.FieldMatcher.create()
                            .declaredClass(spec.fieldOwner,
                                    org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                                    false)
                            .name(spec.fieldName,
                                    org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                                    false);
            org.luckypray.dexkit.query.matchers.UsingFieldMatcher usingField =
                    new org.luckypray.dexkit.query.matchers.UsingFieldMatcher()
                            .field(fieldMatcher)
                            .usingType(org.luckypray.dexkit.query.enums.UsingType.Read);
            java.util.List<org.luckypray.dexkit.query.matchers.UsingFieldMatcher> fields =
                    new java.util.ArrayList<>();
            fields.add(usingField);
            if (spec.semanticFieldName != null) {
                fields.add(new org.luckypray.dexkit.query.matchers.UsingFieldMatcher()
                        .field(org.luckypray.dexkit.query.matchers.FieldMatcher.create()
                                .name(spec.semanticFieldName,
                                        org.luckypray.dexkit.query.enums.StringMatchType.Equals,
                                        false))
                        .usingType(org.luckypray.dexkit.query.enums.UsingType.Read));
            }
            matcher.usingFields(fields);
            if (spec.semanticString != null) {
                matcher.usingStrings(java.util.Collections.singletonList(spec.semanticString));
            }
            if (spec.usingNumber != null) {
                matcher.usingNumbers(
                        java.util.Collections.singletonList((Number) spec.usingNumber));
            }
            org.luckypray.dexkit.result.MethodDataList found = bridge.findMethod(
                    org.luckypray.dexkit.query.FindMethod.create().matcher(matcher));
            QueryResult result = new QueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.MethodData method : found) {
                result.hits.add(plainHit(method));
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new QueryResult(0, error);
        }
    }

    private static QueryResult query(org.luckypray.dexkit.DexKitBridge bridge, String anchor) {
        return query(bridge, new MethodSpec(anchor,
                DexKitZinstantFingerprint.expectedOwner(anchor),
                DexKitZinstantFingerprint.RETURN_VOID,
                DexKitZinstantFingerprint.expectedParamCount(anchor)));
    }

    /**
     * Runs a batch of string-usage queries in one bridge session, keyed by spec id.
     * Each hit carries its invoked callees for caller-linkage fingerprints. Failures
     * are per-spec.
     */
    static java.util.Map<String, QueryResult> scanStrings(String apkPath,
                                                          java.util.List<StringSpec> specs) {
        java.util.Map<String, QueryResult> results = new java.util.LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            org.luckypray.dexkit.query.BatchFindMethodUsingStrings batch =
                    org.luckypray.dexkit.query.BatchFindMethodUsingStrings.create();
            for (StringSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || spec.value.isEmpty()
                        || results.containsKey(spec.id)) {
                    continue;
                }
                batch.addSearchGroup(org.luckypray.dexkit.query.matchers.StringMatchersGroup
                        .create().groupName(spec.id).usingStrings(
                                java.util.Collections.singletonList(spec.value),
                                org.luckypray.dexkit.query.enums.StringMatchType.Contains,
                                false));
                results.put(spec.id, new QueryResult(0, "not run"));
            }
            java.util.Map<String, org.luckypray.dexkit.result.MethodDataList> found =
                    bridge.batchFindMethodUsingStrings(batch);
            for (java.util.Map.Entry<String, org.luckypray.dexkit.result.MethodDataList> group
                    : found.entrySet()) {
                QueryResult result = new QueryResult(group.getValue().size(), "");
                for (org.luckypray.dexkit.result.MethodData method : group.getValue()) {
                    boolean invokes = false;
                    for (StringSpec spec : specs) {
                        if (spec.id.equals(group.getKey())) invokes = spec.withInvokes;
                    }
                    result.hits.add(invokes ? toRawHitWithInvokes(method) : plainHit(method));
                }
                results.put(group.getKey(), result);
            }
            for (StringSpec spec : specs) {
                if (spec == null || spec.id.isEmpty()) {
                    continue;
                }
                QueryResult current = results.get(spec.id);
                if (current != null && "not run".equals(current.error)) {
                    results.put(spec.id, new QueryResult(0, ""));
                }
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (StringSpec spec : specs) {
                if (spec == null || spec.id.isEmpty()) {
                    continue;
                }
                QueryResult current = results.get(spec.id);
                if (current == null || "not run".equals(current.error)) {
                    results.put(spec.id, new QueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    private static RawHit toRawHitWithInvokes(org.luckypray.dexkit.result.MethodData method) {
        java.util.List<RawHit> invoked = new java.util.ArrayList<>();
        try {
            for (org.luckypray.dexkit.result.MethodData callee : method.getInvokes()) {
                invoked.add(new RawHit(callee.getClassName(), callee.getName(),
                        callee.getReturnTypeName(), callee.getParamCount(),
                        normalizeParamTypes(callee.getParamTypeNames()),
                        (callee.getModifiers() & 0x0008) != 0));
            }
        } catch (Throwable ignored) {
        }
        return new RawHit(method.getClassName(), method.getName(),
                method.getReturnTypeName(), method.getParamCount(),
                normalizeParamTypes(method.getParamTypeNames()),
                (method.getModifiers() & 0x0008) != 0, invoked);
    }

    private static QueryResult query(org.luckypray.dexkit.DexKitBridge bridge, MethodSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.MethodMatcher matcher =
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create();
            if (spec.ownerClass != null && !spec.ownerClass.isEmpty()) {
                matcher.declaredClass(org.luckypray.dexkit.query.matchers
                        .ClassMatcher.create().className(spec.ownerClass));
            }
            if (spec.returnType != null && !spec.returnType.isEmpty()) {
                matcher.returnType(spec.returnType);
            }
            if (spec.paramCount != null) {
                matcher.paramCount(spec.paramCount);
            }
            if (spec.usingNumber != null) {
                matcher.usingNumbers(
                        java.util.Collections.singletonList((Number) spec.usingNumber));
            }
            org.luckypray.dexkit.result.MethodDataList found = bridge.findMethod(
                    org.luckypray.dexkit.query.FindMethod.create().matcher(matcher));
            QueryResult result = new QueryResult(found.size(), "");
            boolean withDetail = spec.withDetails && found.size() <= MAX_DETAIL_HITS;
            for (org.luckypray.dexkit.result.MethodData method : found) {
                if (withDetail) {
                    result.hits.add(toRawHitWithDetails(method, spec.withInvokes));
                } else {
                    result.hits.add(plainHit(method));
                }
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new QueryResult(0, error);
        }
    }

    private static RawHit plainHit(org.luckypray.dexkit.result.MethodData method) {
        return new RawHit(method.getClassName(), method.getName(),
                method.getReturnTypeName(), method.getParamCount(),
                normalizeParamTypes(method.getParamTypeNames()),
                (method.getModifiers() & 0x0008) != 0);
    }

    private static RawHit toRawHitWithDetails(org.luckypray.dexkit.result.MethodData method,
                                              boolean withInvokes) {
        RawHit base = withInvokes ? toRawHitWithInvokes(method) : plainHit(method);
        java.util.List<String> usedFields = new java.util.ArrayList<>();
        try {
            for (org.luckypray.dexkit.result.UsingFieldData used
                    : method.getUsingFields()) {
                if (used != null && used.getField() != null) {
                    usedFields.add(used.getField().getClassName()
                            + "#" + used.getField().getName());
                }
            }
        } catch (Throwable ignored) {
        }
        return new RawHit(base.className, base.methodName, base.returnTypeName,
                base.paramCount, base.paramTypeNames, base.isStatic, base.invoked, usedFields);
    }

    /**
     * Runs a batch of class queries against the base APK in one bridge session.
     * The baked query finds bottom-tab-state-shaped classes: an ArrayList field
     * plus int[]/boolean[] array fields. Precise scoring happens in JVM code and
     * live verification in the resolver; the bridge only prefilters.
     */
    static java.util.Map<String, ClassQueryResult> scanClasses(String apkPath,
                                                               java.util.List<ClassSpec> specs) {
        java.util.Map<String, ClassQueryResult> results = new java.util.LinkedHashMap<>();
        if (specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (ClassSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, queryClasses(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (ClassSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new ClassQueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    /** One subclass-of-base query spec, batched into a single bridge session. */
    static final class SubclassSpec {
        final String id;
        final String baseClass;
        final String fieldType;

        SubclassSpec(String id, String baseClass, String fieldType) {
            this.id = id == null ? "" : id;
            this.baseClass = baseClass == null ? "" : baseClass;
            this.fieldType = fieldType == null ? "" : fieldType;
        }
    }

    /**
     * Class names of the subclasses of each spec's base that declare a field of its field
     * type, keyed by spec id. One bridge session for the whole batch: Zalo's R8 pass
     * obfuscates nested androidx types (its adapter base is
     * {@code androidx.recyclerview.widget.z0}, not {@code RecyclerView$Adapter}), so the
     * row family is located by class relation rather than by a library class name.
     */
    static java.util.Map<String, ClassQueryResult> findSubclassesWithField(
            String apkPath, java.util.List<SubclassSpec> specs) {
        java.util.Map<String, ClassQueryResult> results = new java.util.LinkedHashMap<>();
        if (apkPath == null || apkPath.isEmpty() || specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (SubclassSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || spec.baseClass.isEmpty()
                        || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, querySubclassesWithField(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (SubclassSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new ClassQueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    /** One "enum classes using these strings" spec. */
    static final class EnumSpec {
        final String id;
        final String[] usingStrings;

        EnumSpec(String id, String... usingStrings) {
            this.id = id == null ? "" : id;
            this.usingStrings = usingStrings == null ? new String[0] : usingStrings;
        }
    }

    /**
     * Class names of the enums using each spec's strings, keyed by spec id. One bridge
     * session for the whole batch. Field names are matched in the resolver against the
     * live loader; the bridge only prefilters on enum shape plus string usage.
     */
    static java.util.Map<String, ClassQueryResult> findEnumsUsingStrings(String apkPath,
                                                                         java.util.List<EnumSpec> specs) {
        java.util.Map<String, ClassQueryResult> results = new java.util.LinkedHashMap<>();
        if (apkPath == null || apkPath.isEmpty() || specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (EnumSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || spec.usingStrings.length == 0
                        || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, queryEnumsUsingStrings(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (EnumSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new ClassQueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    private static ClassQueryResult queryEnumsUsingStrings(
            org.luckypray.dexkit.DexKitBridge bridge, EnumSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.FieldsMatcher fields =
                    org.luckypray.dexkit.query.matchers.FieldsMatcher.create();
            for (String name : spec.usingStrings) {
                fields.addForName(name);
            }
            org.luckypray.dexkit.result.ClassDataList found = bridge.findClass(
                    org.luckypray.dexkit.query.FindClass.create().matcher(
                            org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                    .superClass("java.lang.Enum")
                                    .usingEqStrings(spec.usingStrings)
                                    .fields(fields)));
            ClassQueryResult result = new ClassQueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.ClassData clazz : found) {
                result.classNames.add(clazz.getName());
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new ClassQueryResult(0, error);
        }
    }

    /** One "methods that invoke this target" spec. */
    static final class InvokerSpec {
        final String id;
        final String targetOwner;
        final String targetMethod;

        InvokerSpec(String id, String targetOwner, String targetMethod) {
            this.id = id == null ? "" : id;
            this.targetOwner = targetOwner == null ? "" : targetOwner;
            this.targetMethod = targetMethod == null ? "" : targetMethod;
        }
    }

    /**
     * Owner classes of the methods that invoke a target method, keyed by spec id. Uses
     * DexKit's matcher-level {@code invokeMethods} filter, which is evaluated natively as a
     * query predicate; it does not extract raw invoke lists (the documented abort hazard).
     */
    static java.util.Map<String, QueryResult> findInvokers(String apkPath,
                                                           java.util.List<InvokerSpec> specs) {
        java.util.Map<String, QueryResult> results = new java.util.LinkedHashMap<>();
        if (apkPath == null || apkPath.isEmpty() || specs == null || specs.isEmpty()) {
            return results;
        }
        org.luckypray.dexkit.DexKitBridge bridge = null;
        try {
            bridge = org.luckypray.dexkit.DexKitBridge.create(apkPath);
            for (InvokerSpec spec : specs) {
                if (spec == null || spec.id.isEmpty() || spec.targetOwner.isEmpty()
                        || spec.targetMethod.isEmpty() || results.containsKey(spec.id)) {
                    continue;
                }
                results.put(spec.id, queryInvokers(bridge, spec));
            }
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            for (InvokerSpec spec : specs) {
                if (spec != null && !spec.id.isEmpty() && !results.containsKey(spec.id)) {
                    results.put(spec.id, new QueryResult(0, error));
                }
            }
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return results;
    }

    private static QueryResult queryInvokers(org.luckypray.dexkit.DexKitBridge bridge,
                                             InvokerSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.MethodMatcher target =
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .name(spec.targetMethod)
                            .declaredClass(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                    .className(spec.targetOwner));
            org.luckypray.dexkit.query.matchers.MethodMatcher caller =
                    org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .invokeMethods(org.luckypray.dexkit.query.matchers.MethodsMatcher
                                    .create().add(target));
            org.luckypray.dexkit.result.MethodDataList found = bridge.findMethod(
                    org.luckypray.dexkit.query.FindMethod.create().matcher(caller));
            QueryResult result = new QueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.MethodData method : found) {
                result.hits.add(plainHit(method));
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new QueryResult(0, error);
        }
    }

    private static ClassQueryResult querySubclassesWithField(
            org.luckypray.dexkit.DexKitBridge bridge, SubclassSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.ClassMatcher matcher =
                    org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                            .superClass(spec.baseClass);
            if (!spec.fieldType.isEmpty()) {
                matcher.fields(org.luckypray.dexkit.query.matchers.FieldsMatcher.create()
                        .addForType(spec.fieldType));
            }
            org.luckypray.dexkit.result.ClassDataList found = bridge.findClass(
                    org.luckypray.dexkit.query.FindClass.create().matcher(matcher));
            ClassQueryResult result = new ClassQueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.ClassData clazz : found) {
                result.classNames.add(clazz.getName());
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new ClassQueryResult(0, error);
        }
    }

    private static ClassQueryResult queryClasses(org.luckypray.dexkit.DexKitBridge bridge,
                                                ClassSpec spec) {
        try {
            org.luckypray.dexkit.query.matchers.FieldsMatcher fields =
                    org.luckypray.dexkit.query.matchers.FieldsMatcher.create();
            fields.addForType("java.util.ArrayList")
                    .addForType("int[]")
                    .addForType("boolean[]");
            org.luckypray.dexkit.result.ClassDataList found = bridge.findClass(
                    org.luckypray.dexkit.query.FindClass.create().matcher(
                            org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                    .fields(fields)));
            ClassQueryResult result = new ClassQueryResult(found.size(), "");
            for (org.luckypray.dexkit.result.ClassData clazz : found) {
                result.classNames.add(clazz.getName());
            }
            return result;
        } catch (Throwable throwable) {
            String error = throwable.getClass().getSimpleName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
            return new ClassQueryResult(0, error);
        }
    }

    /**
     * Normalizes DexKit parameter type names to readable dotted form. Readable names
     * pass through unchanged; JVM descriptors ({@code Ljava/lang/String;}, {@code I})
     * are converted.
     */
    static java.util.List<String> normalizeParamTypes(java.util.List<String> raw) {
        java.util.List<String> normalized = new java.util.ArrayList<>();
        if (raw == null) {
            return normalized;
        }
        for (String name : raw) {
            normalized.add(normalizeTypeName(name));
        }
        return normalized;
    }

    static String normalizeTypeName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.charAt(0) == 'L' && name.endsWith(";")) {
            return name.substring(1, name.length() - 1).replace('/', '.');
        }
        if (name.length() == 1) {
            switch (name.charAt(0)) {
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
        return name;
    }
}
