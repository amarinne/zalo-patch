package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitOriginalPhotoFingerprint;
import com.ez.zalopatch.DexKitOriginalPhotoFingerprint.MethodShape;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One additional family in the existing artifact-bound DexKit scan. */
final class DexKitOriginalPhotoResolver {
    private DexKitOriginalPhotoResolver() { }

    static Map<String, String> resolve(DexKitZinstantResolver.HostIdentity host,
            Map<String, String> diagnostics) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, DexKitBridgeRunner.QueryResult> strings = DexKitBridgeRunner.scanStrings(host.sourceDir,
                Arrays.asList(new DexKitBridgeRunner.StringSpec("quality", DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY, false),
                        new DexKitBridgeRunner.StringSpec("remembered", DexKitOriginalPhotoFingerprint.REMEMBERED_QUALITY, false),
                        new DexKitBridgeRunner.StringSpec("outgoing", DexKitOriginalPhotoFingerprint.PHOTO_PROVIDER, false),
                        new DexKitBridgeRunner.StringSpec("share", DexKitOriginalPhotoFingerprint.SHARE_PHOTOS, true),
                        new DexKitBridgeRunner.StringSpec("camera_state", DexKitOriginalPhotoFingerprint.CAMERA_STATE, true)));
        Map<String, DexKitBridgeRunner.ClassQueryResult> enums = DexKitBridgeRunner.findEnumsUsingStrings(host.sourceDir,
                Collections.singletonList(new DexKitBridgeRunner.EnumSpec("quality", "STANDARD", "HD", "ORIGINAL")));
        DexKitBridgeRunner.ClassQueryResult enumQuery = enums.get("quality");
        if (enumQuery == null || !enumQuery.error.isEmpty()
                || enumQuery.matchCount != enumQuery.classNames.size()) {
            throw new IllegalStateException("quality enum query incomplete");
        }
        List<MethodShape> applies = new ArrayList<>();
        Map<String, DexKitBridgeRunner.QueryResult> applyQuery = DexKitBridgeRunner.scanMethods(host.sourceDir,
                Collections.singletonList(new DexKitBridgeRunner.MethodSpec("apply", DexKitOriginalPhotoFingerprint.MEDIA_ITEM,
                        "void", 1, true, null, true)));
        for (DexKitBridgeRunner.RawHit hit : hits(applyQuery, "apply")) {
            for (String enumClass : enumQuery.classNames) {
                if (DexKitOriginalPhotoFingerprint.isApply(shape(hit), enumClass)) applies.add(shape(hit));
            }
        }
        MethodShape apply = unique(applies, "native apply-quality method");
        Set<String> qualityClasses = new LinkedHashSet<>();
        for (String reference : apply.fields) if (reference.endsWith("#ORIGINAL")) {
            qualityClasses.add(reference.substring(0, reference.indexOf('#')));
        }
        String quality = one(qualityClasses, "photo quality enum");
        Class<?> qualityClass = Class.forName(quality, false, host.loader);
        Set<String> codes = fieldsOfType(apply.fields, qualityClass, int.class);
        Set<String> flags = fieldsOfType(apply.fields,
                Class.forName(DexKitOriginalPhotoFingerprint.MEDIA_ITEM, false, host.loader), boolean.class);
        if (flags.size() != 2) throw new IllegalArgumentException("no_unique_photo_flags");
        List<String> orderedFlags = new ArrayList<>(flags); Collections.sort(orderedFlags);
        result.put(DexKitOriginalPhotoFingerprint.ENUM, quality);
        result.put(DexKitOriginalPhotoFingerprint.CODE, one(codes, "quality code field"));
        result.put(DexKitOriginalPhotoFingerprint.APPLY, apply.name);
        result.put(DexKitOriginalPhotoFingerprint.FLAG_ONE, orderedFlags.get(0));
        result.put(DexKitOriginalPhotoFingerprint.FLAG_TWO, orderedFlags.get(1));

        List<MethodShape> gateCandidates = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(applyQuery, "apply")) if (apply.id().equals(shape(raw).id())) {
            for (DexKitBridgeRunner.RawHit call : raw.invoked) {
                if (call.isStatic && "boolean".equals(call.returnTypeName) && call.paramCount == 0) gateCandidates.add(shape(call));
            }
        }
        MethodShape gate = unique(gateCandidates, "photo quality gate");
        List<MethodShape> remembered = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(strings, "remembered")) {
            if (gate.owner.equals(raw.className) && raw.isStatic && raw.paramCount == 0 && "int".equals(raw.returnTypeName)) remembered.add(shape(raw));
        }
        unique(remembered, "photo policy linkage");
        Map<String, DexKitBridgeRunner.QueryResult> policy = DexKitBridgeRunner.scanMethods(host.sourceDir,
                Collections.singletonList(new DexKitBridgeRunner.MethodSpec("policy", gate.owner, "boolean", 0, true, null, true)));
        List<MethodShape> eligibility = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(policy, "policy")) {
            if (DexKitOriginalPhotoFingerprint.isEligibility(shape(raw), gate.owner, gate.name)) eligibility.add(shape(raw));
        }
        MethodShape eligible = unique(eligibility, "photo eligibility");
        Set<String> entitlement = new LinkedHashSet<>(eligible.calls); entitlement.remove(gate.id());
        String entitled = one(entitlement, "photo entitlement");
        result.put(DexKitOriginalPhotoFingerprint.POLICY, gate.owner);
        result.put(DexKitOriginalPhotoFingerprint.GATE, gate.name);
        result.put(DexKitOriginalPhotoFingerprint.ELIGIBLE, eligible.name);
        result.put(DexKitOriginalPhotoFingerprint.ENTITLEMENT, entitled.substring(entitled.indexOf('#') + 1));

        List<MethodShape> metadataOwners = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(strings, "quality")) {
            if ("<clinit>".equals(raw.methodName)) metadataOwners.add(shape(raw));
        }
        MethodShape metadataOwner = unique(metadataOwners, "picker quality property");
        Class<?> pickerClass = Class.forName(metadataOwner.owner, false, host.loader);
        Field metadata = null;
        int qualityIndex = -1, cameraIndex = -1;
        for (Field field : pickerClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !field.getType().isArray()
                    || field.getType().getComponentType().isPrimitive()) continue;
            int index = DexKitOriginalPhotoFingerprint.propertyIndex(field, DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY);
            if (index < 0) continue;
            if (metadata != null) throw new IllegalArgumentException("ambiguous_picker_metadata");
            metadata = field; qualityIndex = index;
            cameraIndex = DexKitOriginalPhotoFingerprint.propertyIndex(field, DexKitOriginalPhotoFingerprint.CAMERA_PROPERTY);
        }
        if (metadata == null || cameraIndex < 0) throw new IllegalArgumentException("no_picker_quality_metadata");
        Map<String, DexKitBridgeRunner.QueryResult> getters = DexKitBridgeRunner.scanMethods(host.sourceDir,
                Collections.singletonList(new DexKitBridgeRunner.MethodSpec("getter", pickerClass.getName(),
                        "int", 0, true, qualityIndex, true)));
        String propertyArray = pickerClass.getName() + "#" + metadata.getName();
        MethodShape getter = propertyGetter(hits(getters, "getter"), propertyArray);
        List<MethodShape> readers = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(getters, "getter")) if (getter.id().equals(shape(raw).id())) {
            for (DexKitBridgeRunner.RawHit call : raw.invoked) {
                if (!call.isStatic && "java.lang.Object".equals(call.returnTypeName)
                        && call.paramTypeNames.equals(Arrays.asList("java.lang.Object", metadata.getType().getComponentType().getName()))) {
                    readers.add(shape(call));
                }
            }
        }
        MethodShape reader = unique(readers, "picker property reader");
        List<MethodShape> cameraStates = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(strings, "camera_state")) {
            if (pickerClass.getName().equals(raw.className) && "<init>".equals(raw.methodName)) cameraStates.add(shape(raw));
        }
        unique(cameraStates, "picker camera saved state");
        DexKitBridgeRunner.QueryResult callers = DexKitBridgeRunner.findCallingMethods(host.sourceDir, apply.owner, apply.name);
        if (!callers.error.isEmpty() || callers.matchCount != callers.hits.size()) {
            throw new IllegalStateException("apply callers query incomplete");
        }
        Map<String, Integer> selectionUses = new LinkedHashMap<>();
        for (DexKitBridgeRunner.RawHit raw : callers.hits) {
            if (!shape(raw).calls.contains(getter.id())) continue;
            Set<String> selections = fieldsOfList(raw.usedFields, pickerClass);
            for (String selection : selections) selectionUses.put(selection, selectionUses.getOrDefault(selection, 0) + 1);
        }
        Set<String> selections = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> field : selectionUses.entrySet()) if (field.getValue() >= 2) selections.add(field.getKey());
        result.put(DexKitOriginalPhotoFingerprint.PICKER, pickerClass.getName());
        result.put(DexKitOriginalPhotoFingerprint.GET_QUALITY, getter.name);
        result.put(DexKitOriginalPhotoFingerprint.METADATA, metadata.getName());
        result.put(DexKitOriginalPhotoFingerprint.PROPERTY_CLASS, reader.owner);
        result.put(DexKitOriginalPhotoFingerprint.PROPERTY_READ, reader.name);
        result.put(DexKitOriginalPhotoFingerprint.SELECTION, one(selections, "native picker selection list"));

        List<MethodShape> conversions = new ArrayList<>();
        Map<String, DexKitBridgeRunner.QueryResult> conversionQueries = DexKitBridgeRunner.scanMethods(host.sourceDir,
                outgoingSpecs(hits(strings, "outgoing")));
        for (DexKitBridgeRunner.QueryResult query : conversionQueries.values()) {
            if (!query.error.isEmpty() || query.matchCount != query.hits.size()) {
                throw new IllegalStateException("outgoing query incomplete");
            }
            for (DexKitBridgeRunner.RawHit raw : query.hits) {
                if (raw.isStatic || !"run".equals(raw.methodName) || !raw.usedFields.contains(apply.owner + "#" + orderedFlags.get(0))
                        || !raw.usedFields.contains(apply.owner + "#" + orderedFlags.get(1))) continue;
                if (DexKitOriginalPhotoFingerprint.outgoingConstructor(Class.forName(raw.className, false, host.loader)) != null) conversions.add(shape(raw));
            }
        }
        result.put(DexKitOriginalPhotoFingerprint.OUTGOING, unique(conversions, "photo conversion runnable").owner);
        List<MethodShape> shares = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits(strings, "share")) {
            MethodShape method = shape(raw);
            if (DexKitOriginalPhotoFingerprint.SHARE_VIEW.equals(raw.className) && !raw.isStatic
                    && "void".equals(raw.returnTypeName) && raw.paramTypeNames.equals(Arrays.asList("android.os.Bundle"))
                    && method.calls.contains("android.os.BaseBundle#getBoolean")
                    && method.calls.contains("android.os.Bundle#getParcelableArrayList")) shares.add(method);
        }
        result.put(DexKitOriginalPhotoFingerprint.SHARE, unique(shares, "photo share consumer").name);
        DexKitOriginalPhotoFingerprint.bind(result, host.loader);
        diagnostics.put(DexKitOriginalPhotoFingerprint.PREFIX + "state", "resolved");
        return result;
    }

    private static List<DexKitBridgeRunner.MethodSpec> outgoingSpecs(List<DexKitBridgeRunner.RawHit> anchors) {
        List<DexKitBridgeRunner.MethodSpec> specs = new ArrayList<>();
        Set<String> owners = new LinkedHashSet<>();
        for (DexKitBridgeRunner.RawHit anchor : anchors) if ("run".equals(anchor.methodName)) owners.add(anchor.className);
        for (String owner : owners) specs.add(new DexKitBridgeRunner.MethodSpec(owner, owner, "void", 0, true, null, false));
        return specs;
    }
    private static MethodShape propertyGetter(List<DexKitBridgeRunner.RawHit> hits, String metadata) {
        List<MethodShape> matches = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit raw : hits) if (!raw.isStatic && raw.usedFields.contains(metadata)) matches.add(shape(raw));
        return unique(matches, "picker property getter");
    }
    private static List<DexKitBridgeRunner.RawHit> hits(Map<String, DexKitBridgeRunner.QueryResult> queries, String id) {
        DexKitBridgeRunner.QueryResult query = queries.get(id);
        if (query == null || !query.error.isEmpty() || query.matchCount != query.hits.size()) {
            throw new IllegalStateException("query incomplete: " + id);
        }
        return query.hits;
    }
    private static MethodShape shape(DexKitBridgeRunner.RawHit raw) {
        List<String> calls = new ArrayList<>();
        for (DexKitBridgeRunner.RawHit call : raw.invoked) calls.add(call.className + "#" + call.methodName);
        return new MethodShape(raw.className, raw.methodName, raw.returnTypeName, raw.isStatic,
                raw.paramTypeNames, raw.usedFields, calls);
    }
    private static MethodShape unique(List<MethodShape> candidates, String name) {
        MethodShape result = DexKitOriginalPhotoFingerprint.unique(candidates);
        if (result == null) throw new IllegalArgumentException((candidates.isEmpty() ? "no_" : "ambiguous_") + name.replace(' ', '_'));
        return result;
    }
    private static String one(Set<String> candidates, String name) {
        if (candidates.size() != 1) throw new IllegalArgumentException((candidates.isEmpty() ? "no_" : "ambiguous_") + name.replace(' ', '_'));
        return candidates.iterator().next();
    }
    private static Set<String> fieldsOfType(List<String> references, Class<?> owner, Class<?> type) throws Exception {
        Set<String> fields = new LinkedHashSet<>();
        for (String reference : references) if (reference.startsWith(owner.getName() + "#")) {
            String name = reference.substring(reference.indexOf('#') + 1);
            Field field = owner.getDeclaredField(name);
            if (field.getType() == type && !Modifier.isStatic(field.getModifiers())) fields.add(name);
        }
        return fields;
    }
    private static Set<String> fieldsOfList(List<String> references, Class<?> owner) throws Exception {
        Set<String> fields = new LinkedHashSet<>();
        for (String reference : references) if (reference.startsWith(owner.getName() + "#")) {
            String name = reference.substring(reference.indexOf('#') + 1);
            Field field = owner.getDeclaredField(name);
            if (List.class.isAssignableFrom(field.getType()) && !Modifier.isStatic(field.getModifiers())) fields.add(name);
        }
        return fields;
    }
}
