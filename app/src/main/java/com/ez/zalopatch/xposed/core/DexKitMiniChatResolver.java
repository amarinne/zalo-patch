package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitMiniChatFingerprint;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded queries reuse the shared artifact-bound cache and never extract native invoke lists. */
final class DexKitMiniChatResolver {
    private static final String PREFIX = DexKitMiniChatFingerprint.PREFIX;

    private DexKitMiniChatResolver() { }

    static Map<String, String> resolve(DexKitZinstantResolver.HostIdentity host,
            Map<String, String> diagnostics) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        DexKitBridgeRunner.RawHit run = only(query(host, new DexKitBridgeRunner.MethodSpec(
                "task", null, "void", 0, true, null, false,
                Collections.singletonList("MiniChatController"), DexKitMiniChatFingerprint.SERVICE,
                null)), "native_task");
        Class<?> task = Class.forName(run.className, false, host.loader);
        if (!Runnable.class.isAssignableFrom(task) || !"run".equals(run.methodName)) {
            throw new IllegalArgumentException("no_native_task");
        }
        List<Class<?>> controllers = new ArrayList<>();
        for (Constructor<?> ctor : task.getDeclaredConstructors()) {
            if (DexKitMiniChatFingerprint.taskShape(ctor.getParameterTypes())) {
                controllers.add(ctor.getParameterTypes()[0]);
            }
        }
        Class<?> controller = only(controllers, "controller");
        DexKitBridgeRunner.RawHit eligible = only(query(host, new DexKitBridgeRunner.MethodSpec(
                "eligible", controller.getName(), "boolean", 0, true, null, false,
                Collections.emptyList(), null,
                "Landroid/provider/Settings;->canDrawOverlays(Landroid/content/Context;)Z")), "eligibility");
        Set<String> common = flags(run.usedFields, host.loader);
        common.retainAll(flags(eligible.usedFields, host.loader));
        String legacy = only(new ArrayList<>(common), "legacy_flag");
        String capability = owner(legacy);
        Set<String> configFlags = flags(eligible.usedFields, host.loader);
        configFlags.removeIf(field -> owner(field).equals(capability)
                || owner(field).equals(controller.getName()));
        String configFlag = only(new ArrayList<>(configFlags), "configuration_flag");
        DexKitBridgeRunner.RawHit reload = only(query(host,
                new DexKitBridgeRunner.MethodSpec("config", owner(configFlag), "void", 0,
                        true, null, false, "ENABLE_CHAT_BUBBLES_ANDROID_11")), "configuration_reload");
        if (!reload.isStatic || !reload.usedFields.contains(configFlag)) {
            throw new IllegalArgumentException("no_configuration_reload_link");
        }
        List<DexKitBridgeRunner.RawHit> init = query(host,
                new DexKitBridgeRunner.MethodSpec("capability", capability, "void", 0,
                        true, null, false));
        boolean sdk = false;
        for (DexKitBridgeRunner.RawHit method : init) {
            if ("<clinit>".equals(method.methodName)
                    && method.usedFields.contains("android.os.Build$VERSION#SDK_INT")) sdk = true;
        }
        if (!sdk) throw new IllegalArgumentException("no_sdk_capability_link");
        result.put(PREFIX + "task_class", run.className);
        result.put(PREFIX + "controller_class", controller.getName());
        result.put(PREFIX + "eligible_method", eligible.methodName);
        result.put(PREFIX + "capability_class", capability);
        result.put(PREFIX + "legacy_field", name(legacy));
        result.put(PREFIX + "config_class", owner(configFlag));
        result.put(PREFIX + "config_field", name(configFlag));
        result.put(PREFIX + "reload_method", reload.methodName);
        DexKitMiniChatFingerprint.bind(result, host.loader);
        diagnostics.put(PREFIX + "state", "resolved");
        return result;
    }

    private static List<DexKitBridgeRunner.RawHit> query(DexKitZinstantResolver.HostIdentity host,
            DexKitBridgeRunner.MethodSpec spec) {
        DexKitBridgeRunner.QueryResult result = DexKitBridgeRunner.scanMethods(host.sourceDir,
                Collections.singletonList(spec)).get(spec.id);
        if (result == null || !result.error.isEmpty() || result.matchCount != result.hits.size()) {
            throw new IllegalStateException("mini_chat_query_error");
        }
        if (result.matchCount > 8) throw new IllegalArgumentException("ambiguous_" + spec.id);
        return result.hits;
    }

    private static Set<String> flags(List<String> fields, ClassLoader loader) throws Exception {
        Set<String> result = new LinkedHashSet<>();
        for (String identity : fields) {
            Field field = Class.forName(owner(identity), false, loader).getDeclaredField(name(identity));
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == boolean.class) result.add(identity);
        }
        return result;
    }

    private static String owner(String field) { return field.substring(0, field.indexOf('#')); }
    private static String name(String field) { return field.substring(field.indexOf('#') + 1); }
    private static <T> T only(List<T> values, String reason) {
        if (values.size() != 1) throw new IllegalArgumentException(
                (values.isEmpty() ? "no_" : "ambiguous_") + reason);
        return values.get(0);
    }
}
