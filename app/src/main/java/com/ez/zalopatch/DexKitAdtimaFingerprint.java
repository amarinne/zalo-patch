package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** Dependency-free semantic fingerprints for Adtima's offline and tracking gates. */
public final class DexKitAdtimaFingerprint {
    public static final String QUERY_OFFLINE_WINDOW = "adtima.offline_window.literal";
    public static final String QUERY_ADTIMA_CALL = "adtima.offline_window.adtima_call";
    public static final String QUERY_CURRENT_TIME = "adtima.offline_window.current_time";
    public static final String QUERY_LONG_VALUE = "adtima.offline_window.long_value";
    public static final String QUERY_TRACKING_GATE = "adtima.tracking_gate";
    public static final String OFFLINE_WINDOW_LITERAL = "n";
    public static final String ADTIMA_OWNER = "com.adtima.Adtima";
    public static final String CURRENT_TIME_DESCRIPTOR =
            "Ljava/lang/System;->currentTimeMillis()J";
    public static final String LONG_VALUE_DESCRIPTOR = "Ljava/lang/Long;->longValue()J";
    public static final String OFFLINE_WINDOW_CLASS = "symbols.adtima.offline_window_class";
    public static final String OFFLINE_WINDOW_METHOD = "symbols.adtima.offline_window_method";
    public static final String TRACKING_GATE_CLASS = "symbols.adtima.tracking_gate_class";
    public static final String TRACKING_GATE_METHOD = "symbols.adtima.tracking_gate_method";

    private DexKitAdtimaFingerprint() {
    }

    /** One reflected method identity from a complete DexKit query result. */
    public static final class MethodRef {
        public final String className;
        public final String methodName;

        public MethodRef(String className, String methodName) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
        }

        public String descriptor() {
            return methodDescriptor(className, methodName, Collections.<String>emptyList(), "boolean");
        }

        public String display() {
            return className + "#" + methodName + "()Z";
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof MethodRef)) return false;
            MethodRef ref = (MethodRef) other;
            return className.equals(ref.className) && methodName.equals(ref.methodName);
        }

        @Override
        public int hashCode() {
            return 31 * className.hashCode() + methodName.hashCode();
        }
    }

    /** Minimal query result shape; each input list is from one exact DexKit predicate. */
    public static final class MethodHit {
        public final String className;
        public final String methodName;
        public final String returnTypeName;
        public final List<String> paramTypeNames;
        public final boolean isStatic;

        public MethodHit(String className, String methodName, String returnTypeName,
                         List<String> paramTypeNames, boolean isStatic) {
            this.className = className == null ? "" : className;
            this.methodName = methodName == null ? "" : methodName;
            this.returnTypeName = normalize(returnTypeName);
            this.paramTypeNames = paramTypeNames == null
                    ? new ArrayList<String>() : new ArrayList<>(paramTypeNames);
            this.isStatic = isStatic;
        }

        private boolean isStaticBooleanNoArgs() {
            return !className.isEmpty() && !methodName.isEmpty() && isStatic
                    && "boolean".equals(returnTypeName) && paramTypeNames.isEmpty();
        }

        private MethodRef ref() {
            return new MethodRef(className, methodName);
        }
    }

    /** One anchor's fail-closed resolution state. */
    public static final class AnchorResolution {
        public final MethodRef method;
        public final String status;

        AnchorResolution(MethodRef method, String status) {
            this.method = method;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status) && method != null;
        }
    }

    /** Intersects four complete query result sets; no broad shape can authorize the gate. */
    public static AnchorResolution resolveOfflineWindow(List<MethodHit> exactMarker,
            List<MethodHit> adtimaCalls, List<MethodHit> currentTimeCalls,
            List<MethodHit> longValueCalls, boolean completeQueries) {
        if (!completeQueries) return new AnchorResolution(null, "query_error");
        List<MethodRef> marker = qualified(exactMarker);
        List<MethodRef> adtima = qualified(adtimaCalls);
        List<MethodRef> currentTime = qualified(currentTimeCalls);
        List<MethodRef> longValue = qualified(longValueCalls);
        if (hasDuplicates(marker) || hasDuplicates(adtima)
                || hasDuplicates(currentTime) || hasDuplicates(longValue)) {
            return new AnchorResolution(null, "ambiguous_offline_window");
        }
        Set<String> adtimaIds = identities(adtima);
        Set<String> currentTimeIds = identities(currentTime);
        Set<String> longValueIds = identities(longValue);
        List<MethodRef> matches = new ArrayList<>();
        for (MethodRef candidate : marker) {
            String descriptor = candidate.descriptor();
            if (adtimaIds.contains(descriptor) && currentTimeIds.contains(descriptor)
                    && longValueIds.contains(descriptor)) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) return new AnchorResolution(null, "no_offline_window");
        if (matches.size() != 1) return new AnchorResolution(null, "ambiguous_offline_window");
        return new AnchorResolution(matches.get(0), "resolved");
    }

    /** The caller list is constrained natively by the exact offline-window descriptor. */
    public static AnchorResolution resolveTrackingGate(MethodRef offlineWindow,
            List<MethodHit> exactCallers, boolean completeQuery) {
        if (!completeQuery) return new AnchorResolution(null, "query_error");
        if (offlineWindow == null) return new AnchorResolution(null, "no_offline_window");
        List<MethodRef> matches = new ArrayList<>();
        for (MethodHit hit : exactCallers == null
                ? Collections.<MethodHit>emptyList() : exactCallers) {
            if (hit != null && hit.isStaticBooleanNoArgs()
                    && !offlineWindow.equals(hit.ref())) {
                matches.add(hit.ref());
            }
        }
        if (matches.isEmpty()) return new AnchorResolution(null, "no_tracking_gate");
        if (matches.size() != 1) return new AnchorResolution(null, "ambiguous_tracking_gate");
        return new AnchorResolution(matches.get(0), "resolved");
    }

    /** A full family map exists only when both independently unique gates resolve. */
    public static Map<String, String> descriptors(AnchorResolution offline,
                                                  AnchorResolution tracking) {
        if (offline == null || tracking == null || !offline.resolved() || !tracking.resolved()
                || offline.method.equals(tracking.method)) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put(OFFLINE_WINDOW_CLASS, offline.method.className);
        result.put(OFFLINE_WINDOW_METHOD, offline.method.methodName);
        result.put(TRACKING_GATE_CLASS, tracking.method.className);
        result.put(TRACKING_GATE_METHOD, tracking.method.methodName);
        return result;
    }

    private static List<MethodRef> qualified(List<MethodHit> hits) {
        List<MethodRef> qualified = new ArrayList<>();
        if (hits == null) return qualified;
        for (MethodHit hit : hits) {
            if (hit == null || !hit.isStaticBooleanNoArgs()) continue;
            qualified.add(hit.ref());
        }
        return qualified;
    }

    private static boolean hasDuplicates(List<MethodRef> refs) {
        return identities(refs).size() != refs.size();
    }

    private static Set<String> identities(List<MethodRef> refs) {
        Set<String> identities = new LinkedHashSet<>();
        for (MethodRef ref : refs) identities.add(ref.descriptor());
        return identities;
    }

    private static String methodDescriptor(String owner, String name, List<String> params,
                                          String returnType) {
        StringBuilder descriptor = new StringBuilder("L")
                .append(owner.replace('.', '/')).append(";->").append(name).append('(');
        if (params != null) {
            for (String param : params) descriptor.append(typeDescriptor(param));
        }
        return descriptor.append(')').append(typeDescriptor(returnType)).toString();
    }

    private static String typeDescriptor(String type) {
        String normalized = normalize(type);
        switch (normalized) {
            case "void": return "V";
            case "boolean": return "Z";
            case "byte": return "B";
            case "char": return "C";
            case "short": return "S";
            case "int": return "I";
            case "long": return "J";
            case "float": return "F";
            case "double": return "D";
            default:
                if (normalized.startsWith("[")) return normalized.replace('.', '/');
                return "L" + normalized.replace('.', '/') + ";";
        }
    }

    private static String normalize(String type) {
        if (type == null) return "";
        if (type.length() == 1) {
            switch (type) {
                case "V": return "void";
                case "Z": return "boolean";
                case "B": return "byte";
                case "C": return "char";
                case "S": return "short";
                case "I": return "int";
                case "J": return "long";
                case "F": return "float";
                case "D": return "double";
                default: break;
            }
        }
        if (type.startsWith("L") && type.endsWith(";")) {
            return type.substring(1, type.length() - 1).replace('/', '.');
        }
        return type;
    }
}
