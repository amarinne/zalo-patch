package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.SymbolSchema;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

final class SymbolPreflight {
    private SymbolPreflight() {
    }

    static Result inspect(SymbolSchema.Active schema, ClassLoader classLoader) {
        Result result = new Result();
        result.inboxMedia = checkInboxMedia(schema, classLoader, result.inboxMediaErrors);
        result.inboxCategories = checkInboxCategories(
                schema, classLoader, result.inboxCategoryErrors);
        result.me = checkMe(schema, classLoader, result.meErrors);
        result.bottomTabs = checkBottomTabs(schema, classLoader, result.bottomErrors);
        result.zinstantMessage = checkZinstantMessage(
                schema, classLoader, result.zinstantMessageErrors);
        result.zinstantFeed = checkZinstantFeed(
                schema, classLoader, result.zinstantFeedErrors);
        result.statusPrivacy = checkStatusPrivacy(
                schema, classLoader, result.statusPrivacyErrors);
        return result;
    }

    private static Class<?> checkInboxAdapter(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        Class<?> adapter = load(schema.string("symbols.inbox.message_adapter_class", ""),
                loader, errors);
        if (adapter != null) {
            int listSetters = 0;
            for (Method method : adapter.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length > 0 && List.class.isAssignableFrom(parameters[0])) {
                    listSetters++;
                }
            }
            if (listSetters == 0) {
                errors.add("adapter has no List input method");
            }
        }
        return adapter;
    }

    private static boolean checkInboxMedia(SymbolSchema.Active schema, ClassLoader loader,
                                           List<String> errors) {
        checkInboxAdapter(schema, loader, errors);
        load(schema.string("symbols.inbox.media_box_item_class", ""), loader, errors);
        return errors.isEmpty();
    }

    private static boolean checkInboxCategories(SymbolSchema.Active schema, ClassLoader loader,
                                                List<String> errors) {
        checkInboxAdapter(schema, loader, errors);
        Class<?> row = load(schema.string("symbols.inbox.normal_item_class", ""), loader, errors);
        Class<?> conversation = load(schema.string("symbols.inbox.conversation_class", ""),
                loader, errors);
        if (row != null && conversation != null) {
            field(row, schema.string("symbols.inbox.conversation_field", ""),
                    conversation, errors);
            method(row, schema.string("symbols.inbox.row_uid_method", ""),
                    String.class, 0, errors);
        }
        if (conversation != null) {
            field(conversation, schema.string("symbols.inbox.category_int_field", ""),
                    Integer.TYPE, errors);
        }
        return errors.isEmpty();
    }

    private static boolean checkMe(SymbolSchema.Active schema, ClassLoader loader,
                                   List<String> errors) {
        Class<?> tabMe = load(schema.string("symbols.me.tab_me_class", ""), loader, errors);
        if (tabMe != null) {
            method(tabMe, schema.string("symbols.me.current_builder_method", ""),
                    ArrayList.class, 1, errors);
        }
        Class<?> item = load(schema.string("symbols.me.setting_item_class", ""), loader, errors);
        if (item != null) {
            field(item, schema.string("symbols.me.setting_id_field", ""), null, errors);
            field(item, schema.string("symbols.me.setting_title_field", ""), null, errors);
            field(item, schema.string("symbols.me.setting_summary_field", ""), null, errors);
        }
        return errors.isEmpty();
    }

    private static boolean checkBottomTabs(SymbolSchema.Active schema, ClassLoader loader,
                                           List<String> errors) {
        JSONObject bottom = schema.root.optJSONObject("symbols") == null ? null
                : schema.root.optJSONObject("symbols").optJSONObject("bottom_tabs");
        JSONArray definitions = bottom == null ? null : bottom.optJSONArray("current_tab_symbols");
        if (definitions == null || definitions.length() == 0) {
            errors.add("current tab symbols unavailable");
            return false;
        }
        boolean matched = false;
        for (int index = 0; index < definitions.length(); index++) {
            JSONObject definition = definitions.optJSONObject(index);
            if (definition == null) {
                continue;
            }
            ArrayList<String> candidateErrors = new ArrayList<>();
            Class<?> state = load(definition.optString("state_class", ""), loader, candidateErrors);
            load(definition.optString("enum_class", ""), loader, candidateErrors);
            if (state != null) {
                JSONObject indexes = definition.optJSONObject("index_fields");
                if (indexes != null) {
                    java.util.Iterator<String> keys = indexes.keys();
                    while (keys.hasNext()) {
                        field(state, indexes.optString(keys.next(), ""), Integer.TYPE,
                                candidateErrors);
                    }
                }
                field(state, definition.optString("icons_field", ""), int[].class,
                        candidateErrors);
                field(state, definition.optString("preloaded_field", ""), boolean[].class,
                        candidateErrors);
                JSONObject methods = bottom.optJSONObject("current_methods");
                if (methods == null) {
                    candidateErrors.add("current state methods unavailable");
                } else {
                    java.util.Iterator<String> keys = methods.keys();
                    while (keys.hasNext()) {
                        methodNamed(state, methods.optString(keys.next(), ""), candidateErrors);
                    }
                }
            }
            if (candidateErrors.isEmpty()) {
                matched = true;
                break;
            }
            errors.addAll(candidateErrors);
        }
        return matched;
    }

    private static boolean checkZinstantMessage(SymbolSchema.Active schema, ClassLoader loader,
                                                List<String> errors) {
        Class<?> message = load(schema.string("symbols.zinstant.ad_item_view_class", ""),
                loader, errors);
        if (message != null) {
            method(message, schema.string("symbols.zinstant.ad_bind_method", ""),
                    Void.TYPE, 3, errors);
        }
        return errors.isEmpty();
    }

    private static boolean checkZinstantFeed(SymbolSchema.Active schema, ClassLoader loader,
                                             List<String> errors) {
        Class<?> feed = load(schema.string("symbols.zinstant.feed_ads_class", ""), loader, errors);
        if (feed != null) {
            method(feed, schema.string("symbols.zinstant.feed_bind_method", ""),
                    Void.TYPE, 4, errors);
        }
        return errors.isEmpty();
    }

    private static boolean checkStatusPrivacy(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        Class<?> ack = load(schema.string("symbols.chat.seen_ack_class", ""), loader, errors);
        Class<?> manager = load(schema.string(
                "symbols.chat.send_seen_manager_class", ""), loader, errors);
        Class<?> repository = load(schema.string(
                "symbols.chat.message_repository_class", ""), loader, errors);
        if (ack != null) {
            field(ack, schema.string("symbols.chat.seen_ack_type_field", ""),
                    Integer.TYPE, errors);
        }
        if (manager != null && ack != null) {
            methodExact(manager, schema.string("symbols.chat.send_seen_single_method", ""),
                    Void.TYPE, errors, ack);
            methodExact(manager, schema.string("symbols.chat.send_seen_batch_method", ""),
                    Void.TYPE, errors, ArrayList.class);
        }
        if (repository != null) {
            methodExact(repository, schema.string("symbols.chat.send_ack_method", ""),
                    Void.TYPE, errors, List.class, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE);
            methodExact(repository, schema.string("symbols.chat.send_typing_method", ""),
                    Void.TYPE, errors, String.class, Integer.TYPE, Boolean.TYPE, Boolean.TYPE);
        }
        return errors.isEmpty();
    }

    private static Class<?> load(String name, ClassLoader loader, List<String> errors) {
        if (name.isEmpty()) {
            errors.add("class name missing");
            return null;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable throwable) {
            errors.add("class missing: " + name);
            return null;
        }
    }

    private static void field(Class<?> owner, String name, Class<?> type, List<String> errors) {
        if (name.isEmpty()) {
            errors.add(owner.getName() + " field name missing");
            return;
        }
        try {
            Field field = owner.getDeclaredField(name);
            if (type != null && field.getType() != type) {
                errors.add(owner.getName() + "#" + name + " field type changed");
            }
        } catch (Throwable throwable) {
            errors.add(owner.getName() + "#" + name + " field missing");
        }
    }

    private static void method(Class<?> owner, String name, Class<?> returnType,
                               int parameterCount, List<String> errors) {
        if (name.isEmpty()) {
            errors.add(owner.getName() + " method name missing");
            return;
        }
        int matches = 0;
        for (Method method : owner.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterTypes().length == parameterCount
                    && (returnType == null || returnType.isAssignableFrom(method.getReturnType()))) {
                matches++;
            }
        }
        if (matches != 1) {
            errors.add(owner.getName() + "#" + name + " expected one matching method, found "
                    + matches);
        }
    }

    private static void methodNamed(Class<?> owner, String name, List<String> errors) {
        if (name.isEmpty()) {
            errors.add(owner.getName() + " method name missing");
            return;
        }
        for (Method method : owner.getDeclaredMethods()) {
            if (name.equals(method.getName())) {
                return;
            }
        }
        errors.add(owner.getName() + "#" + name + " method missing");
    }

    private static void methodExact(Class<?> owner, String name, Class<?> returnType,
                                    List<String> errors, Class<?>... parameterTypes) {
        if (name.isEmpty()) {
            errors.add(owner.getName() + " method name missing");
            return;
        }
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            if (method.getReturnType() != returnType) {
                errors.add(owner.getName() + "#" + name + " return type changed");
            }
        } catch (Throwable throwable) {
            errors.add(owner.getName() + "#" + name + " signature changed");
        }
    }

    static final class Result {
        boolean inboxMedia;
        boolean inboxCategories;
        boolean me;
        boolean bottomTabs;
        boolean zinstantMessage;
        boolean zinstantFeed;
        boolean statusPrivacy;
        final List<String> inboxMediaErrors = new ArrayList<>();
        final List<String> inboxCategoryErrors = new ArrayList<>();
        final List<String> meErrors = new ArrayList<>();
        final List<String> bottomErrors = new ArrayList<>();
        final List<String> zinstantMessageErrors = new ArrayList<>();
        final List<String> zinstantFeedErrors = new ArrayList<>();
        final List<String> statusPrivacyErrors = new ArrayList<>();

        String reason(List<String> errors) {
            return errors.isEmpty() ? "structural preflight failed" : String.join("; ", errors);
        }

        /** Number of anchor families whose structure resolved, out of {@link #total()}. */
        int resolved() {
            int count = 0;
            if (inboxMedia) count++;
            if (inboxCategories) count++;
            if (me) count++;
            if (bottomTabs) count++;
            if (zinstantMessage) count++;
            if (zinstantFeed) count++;
            if (statusPrivacy) count++;
            return count;
        }

        int total() {
            return 7;
        }

        /** Per-family outcome, for a probe row that has to be read without the source at hand. */
        String breakdown() {
            StringBuilder value = new StringBuilder();
            append(value, "inbox_media", inboxMedia);
            append(value, "inbox_categories", inboxCategories);
            append(value, "me", me);
            append(value, "bottom_tabs", bottomTabs);
            append(value, "zinstant_message", zinstantMessage);
            append(value, "zinstant_feed", zinstantFeed);
            append(value, "status_privacy", statusPrivacy);
            return value.toString();
        }

        private static void append(StringBuilder value, String name, boolean resolved) {
            if (value.length() > 0) value.append(' ');
            value.append(name).append('=').append(resolved ? "ok" : "no");
        }
    }
}
