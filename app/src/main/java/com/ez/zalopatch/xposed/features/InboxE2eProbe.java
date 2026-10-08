package com.ez.zalopatch.xposed.features;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/** Reads the native adapter's displayed items; it never runs the module classifier. */
final class InboxE2eProbe {
    static final String ACTION = "com.ez.zalopatch.behave.INBOX_E2E";
    private static final String FEATURE = "inbox.e2e";
    private static final int MAX_ROWS = 500;

    static void register(Supplier<InboxListUpdate> update, Supplier<View> recycler, Supplier<View> strangers,
                         Supplier<String> category, Supplier<String> itemMethod,
                         Supplier<String> uidMethod) {
        Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) throw new IllegalStateException("application context unavailable");
        androidx.core.content.ContextCompat.registerReceiver(context, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!debugEnabled() || intent == null || !ACTION.equals(intent.getAction())) return;
                String run = intent.getStringExtra("run_id");
                String salt = intent.getStringExtra("salt");
                String request = intent.getStringExtra("request_id");
                String surface = intent.getStringExtra("surface");
                if (run == null || !run.matches("[a-zA-Z0-9-]{1,60}")
                        || request == null || !request.matches("[a-zA-Z0-9-]{1,60}")
                        || salt == null || !salt.matches("[a-f0-9]{32,64}")
                        || (!"inbox".equals(surface) && !"stranger".equals(surface))) return;
                JSONObject result = new JSONObject();
                try {
                    InboxListUpdate current = update.get();
                    View view = "stranger".equals(surface) ? strangers.get() : recycler.get();
                    if (current == null || view == null || !view.isShown()
                            || !view.isAttachedToWindow()) {
                        throw new IllegalStateException("visible_inbox_adapter_unavailable");
                    }
                    Object adapter = view.getClass().getMethod("getAdapter").invoke(view);
                    if (adapter == null || ("inbox".equals(surface) && adapter != current.adapter)) {
                        throw new IllegalStateException("native_adapter_identity_mismatch");
                    }
                    Method mainItem = findItemMethod(current.adapter.getClass(), itemMethod.get());
                    Method item = "inbox".equals(surface) ? mainItem
                            : findNativeItemMethod(adapter.getClass(), mainItem.getReturnType());
                    JSONArray source = new JSONArray();
                    if (current.source.size() > MAX_ROWS) throw new IllegalStateException("source_row_limit");
                    for (Object row : current.source) source.put(identity(row, uidMethod.get(), salt));
                    JSONArray displayed = new JSONArray();
                    boolean ended = false;
                    for (int index = 0; index <= MAX_ROWS; index++) {
                        Object row = item.invoke(adapter, index);
                        if (row == null) { ended = true; break; }
                        if (index == MAX_ROWS) break;
                        displayed.put(identity(row, uidMethod.get(), salt));
                    }
                    if (!ended) throw new IllegalStateException("displayed_row_limit");
                    result.put("source", source);
                    result.put("displayed", displayed);
                    result.put("category", category.get());
                    result.put("surface", surface);
                    result.put("visible_adapter", true);
                } catch (Throwable failure) {
                    // No host exception text, row labels, or raw account identifiers leave Zalo.
                    result = new JSONObject();
                    try { result.put("blocked", failure instanceof IllegalStateException
                            ? failure.getMessage() : "native_adapter_reader_unavailable"); }
                    catch (org.json.JSONException ignoredError) { }
                }
                SelfCheckRegistry.markStatus(FEATURE, "active", "e2e:inbox:" + run + ":" + request,
                        result.toString(), "");
            }
        }, new IntentFilter(ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private static Method findNativeItemMethod(Class<?> adapter, Class<?> itemType) throws ReflectiveOperationException {
        Method found = null;
        for (Method candidate : adapter.getMethods()) {
            Class<?>[] args = candidate.getParameterTypes();
            if (args.length != 1 || args[0] != int.class || candidate.isBridge()
                    || java.lang.reflect.Modifier.isStatic(candidate.getModifiers())
                    || !itemType.isAssignableFrom(candidate.getReturnType())) continue;
            if (found != null) throw new NoSuchMethodException("ambiguous native item accessor");
            found = candidate;
        }
        if (found == null) throw new NoSuchMethodException("missing native item accessor");
        found.setAccessible(true);
        return found;
    }

    private static Method findItemMethod(Class<?> type, String name) throws ReflectiveOperationException {
        if (name == null || name.isEmpty()) throw new NoSuchMethodException("missing item accessor");
        Method item = type.getMethod(name, int.class);
        // The exact profile and native invocation determine the accessor; never guess a list field.
        if (java.lang.reflect.Modifier.isStatic(item.getModifiers()) || item.getReturnType().isPrimitive()
                || item.getReturnType() == Object.class) throw new NoSuchMethodException("invalid item accessor");
        item.setAccessible(true);
        return item;
    }

    private static String identity(Object row, String methodName, String salt) throws Exception {
        if (row == null) throw new IllegalStateException("null_source_row");
        Method method = row.getClass().getMethod(methodName);
        if (method.getReturnType() != String.class || java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("invalid_row_identity_accessor");
        }
        method.setAccessible(true);
        String uid = (String) method.invoke(row);
        if (uid == null || uid.isEmpty()) throw new IllegalStateException("empty_row_identity");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((salt + ":" + uid).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private static boolean debugEnabled() {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return "1".equals(properties.getMethod("get", String.class, String.class)
                    .invoke(null, "debug.zalopatch", "0"));
        } catch (ReflectiveOperationException ignored) { return false; }
    }
}
