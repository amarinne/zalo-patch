package com.ez.zalopatch.xposed.core;

import com.ez.zalopatch.DexKitCallFingerprint;
import com.ez.zalopatch.DexKitInboxFingerprint;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.xposed.features.CallRecordingLifecycle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

final class SymbolPreflight {
    /** Stable Zalo call screen; the audio lifecycle boundary hangs off it. */
    private static final String CALL_ACTIVITY_CLASS = "zm.voip.ui.incall.ZmInCallActivity";
    /** Stable ZRTC peer bridge; exact native entry names below. */
    private static final String STABLE_CALL_PEER_JNI_CLASS = "com.vng.zing.vn.zrtc.PeerJNI";
    /** Stable ZRTC call-callback base; replaces the per-release mapped letter. */
    private static final String STABLE_CALL_CALLBACK_CLASS = "com.vng.zing.vn.zrtc.CallCallback";

    private SymbolPreflight() {
    }

    static Result inspect(SymbolSchema.Active schema, ClassLoader classLoader) {
        Result result = new Result();
        // Every family is guarded separately. A linkage error while resolving one family's
        // obfuscated members must not discard the others: callers (overlay adoption, feature
        // gating) treat a thrown inspection as "nothing resolved".
        try {
            result.inboxMedia = checkInboxMedia(schema, classLoader, result.inboxMediaErrors);
        } catch (Throwable throwable) {
            result.inboxMediaErrors.add(familyError("inbox_media", throwable));
        }
        try {
            result.inboxCategories = checkInboxCategories(
                    schema, classLoader, result.inboxCategoryErrors);
        } catch (Throwable throwable) {
            result.inboxCategoryErrors.add(familyError("inbox_categories", throwable));
        }
        try {
            result.me = checkMe(schema, classLoader, result.meErrors);
        } catch (Throwable throwable) {
            result.meErrors.add(familyError("me", throwable));
        }
        try {
            result.bottomTabs = checkBottomTabs(schema, classLoader, result.bottomErrors);
        } catch (Throwable throwable) {
            result.bottomErrors.add(familyError("bottom_tabs", throwable));
        }
        try {
            result.zinstantMessage = checkZinstantMessage(
                    schema, classLoader, result.zinstantMessageErrors);
        } catch (Throwable throwable) {
            result.zinstantMessageErrors.add(familyError("zinstant_message", throwable));
        }
        try {
            result.zinstantFeed = checkZinstantFeed(
                    schema, classLoader, result.zinstantFeedErrors);
        } catch (Throwable throwable) {
            result.zinstantFeedErrors.add(familyError("zinstant_feed", throwable));
        }
        try {
            result.statusPrivacy = checkStatusPrivacy(
                    schema, classLoader, result.statusPrivacyErrors);
        } catch (Throwable throwable) {
            result.statusPrivacyErrors.add(familyError("status_privacy", throwable));
        }
        try {
            result.passcodeGrace = checkPasscodeGrace(
                    schema, classLoader, result.passcodeGraceErrors);
        } catch (Throwable throwable) {
            result.passcodeGraceErrors.add(familyError("passcode_grace", throwable));
        }
        try {
            result.backupScheduled = checkBackupScheduled(
                    schema, classLoader, result.backupScheduledErrors);
        } catch (Throwable throwable) {
            result.backupScheduledErrors.add(familyError("backup_scheduled", throwable));
        }
        try {
            result.webviewExternalize = checkWebviewExternalize(
                    schema, classLoader, result.webviewErrors);
        } catch (Throwable throwable) {
            result.webviewErrors.add(familyError("webview_externalize", throwable));
        }
        try {
            result.telemetryDao = checkTelemetry(
                    schema, classLoader, result.telemetryDaoErrors);
        } catch (Throwable throwable) {
            result.telemetryDaoErrors.add(familyError("telemetry_dao", throwable));
        }
        try {
            result.callRecording = checkCallRecording(
                    schema, classLoader, result.callRecordingErrors);
        } catch (Throwable throwable) {
            result.callRecordingErrors.add(familyError("call_recording", throwable));
        }
        try {
            result.inboxRows = checkInboxRows(schema, classLoader, result.inboxRowsErrors);
        } catch (Throwable throwable) {
            result.inboxRowsErrors.add(familyError("inbox_rows", throwable));
        }
        try {
            result.bottomTabsSymbols = checkBottomTabsSymbols(
                    schema, classLoader, result.bottomTabsSymbolsErrors);
        } catch (Throwable throwable) {
            result.bottomTabsSymbolsErrors.add(familyError("bottom_tabs_symbols", throwable));
        }
        try {
            result.chatReaction = checkChatReaction(
                    schema, classLoader, result.chatReactionErrors);
        } catch (Throwable throwable) {
            result.chatReactionErrors.add(familyError("chat_reaction", throwable));
        }
        try {
            result.zinstantSymbols = checkZinstantSymbols(
                    schema, classLoader, result.zinstantSymbolsErrors);
        } catch (Throwable throwable) {
            result.zinstantSymbolsErrors.add(familyError("zinstant_symbols", throwable));
        }
        return result;
    }

    private static String familyError(String family, Throwable throwable) {
        return family + ": " + throwable.getClass().getSimpleName()
                + (throwable.getMessage() == null ? "" : " " + throwable.getMessage());
    }

    /**
     * The inbox adapter. The pinned class is optional: the feature discovers the live
     * adapter from the MessagesView attach and already logs `inbox.filter` against it, so
     * a stale bundled name must not disable the family. When the pinned name does resolve,
     * its list-setter shape is still validated.
     */
    private static Class<?> checkInboxAdapter(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        String name = schema.string("symbols.inbox.message_adapter_class", "");
        Class<?> adapter = null;
        if (name != null && !name.isEmpty()) {
            try {
                adapter = Class.forName(name, false, loader);
            } catch (Throwable ignored) {
                adapter = null; // runtime discovery is the tier that carries the family
            }
        }
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

    /**
     * Media Box hiding. The layout route collapses the inflated
     * {@code item_channel_media_box} root by resource name and needs no symbols, so the
     * pinned row class is optional; only the adapter discovery tier matters.
     */
    private static boolean checkInboxMedia(SymbolSchema.Active schema, ClassLoader loader,
                                           List<String> errors) {
        checkInboxAdapter(schema, loader, errors);
        return errors.isEmpty();
    }

    /**
     * Category discrimination. Normal rows are recognized structurally by the feature
     * (a Conversation-typed field) and the pinned row class was retired, so this requires
     * the Conversation anchors the classification actually reads: the conversation class,
     * its uid field, and the native category discriminator the overlay resolves.
     */
    private static boolean checkInboxCategories(SymbolSchema.Active schema, ClassLoader loader,
                                                List<String> errors) {
        checkInboxAdapter(schema, loader, errors);
        Class<?> conversation = load(schema.string("symbols.inbox.conversation_class", ""),
                loader, errors);
        if (conversation != null) {
            field(conversation, schema.string("symbols.inbox.category_int_field", ""),
                    Integer.TYPE, errors);
            field(conversation, schema.string("symbols.inbox.conversation_uid_field", ""),
                    String.class, errors);
        }
        // Optional pinned-row validation: when the bundled row class still resolves, its
        // Conversation field and uid method must have the expected shape. A stale name is
        // not a family failure because structural recognition carries normal rows.
        String rowName = schema.string("symbols.inbox.normal_item_class", "");
        Class<?> row = null;
        if (rowName != null && !rowName.isEmpty()) {
            try {
                row = Class.forName(rowName, false, loader);
            } catch (Throwable ignored) {
                row = null;
            }
        }
        if (row != null && conversation != null) {
            field(row, schema.string("symbols.inbox.conversation_field", ""),
                    conversation, errors);
            method(row, schema.string("symbols.inbox.row_uid_method", ""),
                    String.class, 0, errors);
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
        // Item identity is runtime-derived (observed builder products / item shape);
        // the pinned item class is retired. The id/title/summary field names must
        // still be present for marker extraction; reads stay fail-soft per field.
        present(schema.string("symbols.me.setting_id_field", ""), "me id field", errors);
        present(schema.string("symbols.me.setting_title_field", ""), "me title field", errors);
        present(schema.string("symbols.me.setting_summary_field", ""), "me summary field", errors);
        return errors.isEmpty();
    }

    private static void present(String name, String label, List<String> errors) {
        if (name == null || name.isEmpty()) {
            errors.add(label + " name missing");
        }
    }

    private static boolean checkBottomTabs(SymbolSchema.Active schema, ClassLoader loader,
                                           List<String> errors) {
        JSONObject bottom = schema.root.optJSONObject("symbols") == null ? null
                : schema.root.optJSONObject("symbols").optJSONObject("bottom_tabs");
        JSONArray definitions = bottom == null ? null : bottom.optJSONArray("current_tab_symbols");
        if (definitions == null || definitions.length() == 0) {
            errors.add("symbols.bottom_tabs.current_tab_symbols unavailable");
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
                if (definition.optBoolean("preserve_icon_arrays", false)) {
                    field(state, definition.optString("tabs_field", ""), java.util.ArrayList.class,
                            candidateErrors);
                }
                JSONObject enabled = definition.optJSONObject("enabled_fields");
                if (enabled != null) {
                    java.util.Iterator<String> enabledKeys = enabled.keys();
                    while (enabledKeys.hasNext()) {
                        field(state, enabled.optString(enabledKeys.next(), ""), Boolean.TYPE,
                                candidateErrors);
                    }
                }
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

    /**
     * Preflight for DexKit-resolved pilot descriptors. The owners are the stable public view
     * classes from the shared fingerprint definition (never an obfuscated name); each resolved
     * bind name must denote exactly one void method with the anchor's parameter count. This is
     * the second gate: the scan already evaluated the same predicates through DexKit, and this
     * revalidates them against the live class loader before any hook installs.
     */
    static boolean checkZinstantDescriptors(ClassLoader loader, String adBind, String feedBind,
                                            List<String> errors) {
        if ((adBind == null || adBind.isEmpty()) && (feedBind == null || feedBind.isEmpty())) {
            errors.add("no dexkit pilot descriptors");
            return false;
        }
        if (adBind != null && !adBind.isEmpty()) {
            checkZinstantDescriptor(loader,
                    com.ez.zalopatch.DexKitZinstantFingerprint.OWNER_AD_VIEW, adBind,
                    com.ez.zalopatch.DexKitZinstantFingerprint.AD_PARAM_COUNT, errors);
        }
        if (feedBind != null && !feedBind.isEmpty()) {
            checkZinstantDescriptor(loader,
                    com.ez.zalopatch.DexKitZinstantFingerprint.OWNER_FEED_ADS, feedBind,
                    com.ez.zalopatch.DexKitZinstantFingerprint.FEED_PARAM_COUNT, errors);
        }
        return errors.isEmpty();
    }

    private static void checkZinstantDescriptor(ClassLoader loader, String owner, String name,
                                                int parameterCount, List<String> errors) {
        Class<?> view = load(owner, loader, errors);
        if (view == null) {
            return;
        }
        if (!android.view.View.class.isAssignableFrom(view)) {
            errors.add(owner + " is not a View subtype");
        }
        if (view.getDeclaredConstructors().length == 0) {
            errors.add(owner + " has no constructor");
        }
        method(view, name, Void.TYPE, parameterCount, errors);
    }

    private static boolean checkStatusPrivacy(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        // Repository route (typing + direct seen ack) is required.
        Class<?> repository = load(schema.string(
                "symbols.chat.message_repository_class", ""), loader, errors);
        if (repository != null) {
            checkChatRepository(repository,
                    schema.string("symbols.chat.send_ack_method", ""),
                    schema.string("symbols.chat.send_typing_method", ""), errors);
        }
        // Seen-queue route is optional: validate only when its classes resolve and never
        // fail the family on it, so typing and direct ack stay available when the ack
        // type field is unresolved (its semantics need separate evidence).
        Class<?> ack = tryLoad(schema.string("symbols.chat.seen_ack_class", ""), loader);
        Class<?> manager = tryLoad(schema.string(
                "symbols.chat.send_seen_manager_class", ""), loader);
        if (manager != null && ack != null) {
            methodExact(manager, schema.string("symbols.chat.send_seen_single_method", ""),
                    Void.TYPE, new ArrayList<>(), ack);
            methodExact(manager, schema.string("symbols.chat.send_seen_batch_method", ""),
                    Void.TYPE, new ArrayList<>(), ArrayList.class);
            field(ack, schema.string("symbols.chat.seen_ack_type_field", ""),
                    Integer.TYPE, new ArrayList<>());
        }
        return errors.isEmpty();
    }

    /** Live check: the resolved chat repository declares both send shapes. */
    static void checkChatRepository(Class<?> repository, String ackMethod, String typingMethod,
                                    List<String> errors) {
        if (repository == null) {
            return;
        }
        methodExact(repository, ackMethod, Void.TYPE, errors,
                List.class, Boolean.TYPE, Boolean.TYPE, Boolean.TYPE);
        methodExact(repository, typingMethod, Void.TYPE, errors,
                String.class, Integer.TYPE, Boolean.TYPE, Boolean.TYPE);
    }

    /** Loads a class, returning null without recording an error when it is absent. */
    private static Class<?> tryLoad(String name, ClassLoader loader) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Call recording family. The neighbouring profile's symbols may be entirely
     * unrelated on an unmapped artifact: on 260901903 {@code zh.a} (the 260802903 peer
     * manager) is a date/TimeZone helper, so the peer-handle chain resolved to nothing
     * and the feature only discovered it when a real call fired a callback. Validate the
     * full chain up front so a stale family reports stale and never arms.
     */
    private static boolean checkCallRecording(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        // T1a: only the stable ZRTC core gates this result. The obfuscated
        // activity/peer-manager/callback letters below are shape-checked into the
        // advisory channel; their drift is reported at use time inside the feature
        // (resolveCurrentSession) and never blocks the core here.
        List<String> coreErrors = new ArrayList<>();
        Class<?> peer = load(STABLE_CALL_PEER_JNI_CLASS, loader, coreErrors);
        if (peer != null) {
            methodExact(peer, "zrtc_peer_start_record_audio", Void.TYPE, coreErrors,
                    Long.TYPE, Boolean.TYPE, String.class);
            methodExact(peer, "zrtc_peer_is_in_call", Boolean.TYPE, coreErrors,
                    Long.TYPE);
            methodNamed(peer, "zrtc_peer_register_callback", coreErrors);
            methodNamed(peer, "zrtc_peer_make_call", coreErrors);
            methodNamed(peer, "zrtc_peer_incoming_call", coreErrors);
            methodNamed(peer, "zrtc_peer_register_in_audio_stream", coreErrors);
            methodNamed(peer, "zrtc_peer_register_out_audio_stream", coreErrors);
        }
        load(STABLE_CALL_CALLBACK_CLASS, loader, coreErrors);
        load(CALL_ACTIVITY_CLASS, loader, coreErrors);
        errors.addAll(coreErrors);
        Class<?> activity = tryLoad(CALL_ACTIVITY_CLASS, loader);
        if (activity != null) {
            String readyMethod = schema.string("symbols.call_recording.activity_ready_method", "");
            if (declaredMethodInHierarchy(activity, readyMethod) == null) {
                errors.add(CALL_ACTIVITY_CLASS + "#" + readyMethod + " missing");
            }
            String stateField = schema.string(
                    "symbols.call_recording.activity_call_state_field", "");
            Field state = declaredFieldInHierarchy(activity, stateField);
            if (state == null) {
                errors.add(CALL_ACTIVITY_CLASS + "#" + stateField + " field missing");
            } else {
                String connected = schema.string(
                        "symbols.call_recording.activity_connected_method", "");
                Method method = declaredMethodInHierarchy(state.getType(), connected);
                if (method == null || method.getParameterTypes().length != 0
                        || method.getReturnType() != Boolean.TYPE) {
                    errors.add(state.getType().getName() + "#" + connected
                            + " connected predicate missing");
                }
            }
        }
        load(schema.string("symbols.call_recording.callback_class", ""), loader, errors);
        Class<?> manager = load(schema.string("symbols.call_recording.peer_manager_class", ""),
                loader, errors);
        if (manager != null) {
            String instanceMethod = schema.string(
                    "symbols.call_recording.peer_manager_instance_method", "");
            Method instance = declaredMethodInHierarchy(manager, instanceMethod);
            if (instance == null || instance.getParameterTypes().length != 0
                    || !Modifier.isStatic(instance.getModifiers())) {
                errors.add(manager.getName() + "#" + instanceMethod
                        + " static accessor missing");
            }
            String containerName = schema.string(
                    "symbols.call_recording.peer_container_field", "");
            Field container = declaredFieldInHierarchy(manager, containerName);
            if (container == null) {
                errors.add(manager.getName() + "#" + containerName + " field missing");
            } else {
                String handleName = schema.string(
                        "symbols.call_recording.peer_handle_field", "");
                Field handle = declaredFieldInHierarchy(container.getType(), handleName);
                if (handle == null || handle.getType() != Long.TYPE) {
                    errors.add(container.getType().getName() + "#" + handleName
                            + " long handle missing");
                }
            }
        }
        return coreErrors.isEmpty();
    }

    /**
     * Live validation for a DexKit-resolved deleted-group store: the class must exist,
     * hold a static field of its own type, and declare an instance {@code (String)Z}
     * membership check. Used before the overlay arms the filter.
     */
    static boolean checkDexkitDeletedGroup(ClassLoader loader, String className,
                                           String fieldName, String methodName,
                                           List<String> errors) {
        if (className == null || className.isEmpty()
                || fieldName == null || fieldName.isEmpty()
                || methodName == null || methodName.isEmpty()) {
            errors.add("deleted-group anchors incomplete");
            return false;
        }
        try {
            Class<?> store = Class.forName(className, false, loader);
            Field field = declaredFieldInHierarchy(store, fieldName);
            if (field == null || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != store) {
                errors.add(className + "#" + fieldName + " singleton missing");
                return false;
            }
            Method check = declaredMethodInHierarchy(store, methodName);
            if (check == null || Modifier.isStatic(check.getModifiers())
                    || check.getParameterTypes().length != 1
                    || check.getParameterTypes()[0] != String.class
                    || check.getReturnType() != Boolean.TYPE) {
                errors.add(className + "#" + methodName + " membership check missing");
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            errors.add(familyError("inbox_deleted_group", throwable));
            return false;
        }
    }

    /**
     * Inbox row-behavior symbols: friend-manager follow chain, topOut marker chain, box
     * row classes, and the MessagesView adapter field. These feed OA detection, box
     * filtering, and native navigation; each is checked against the live host so a
     * drifted letter fails closed per sub-behavior instead of misclassifying rows.
     */
    private static boolean checkInboxRows(SymbolSchema.Active schema, ClassLoader loader,
                                          List<String> errors) {
        Class<?> view = load(schema.string("symbols.inbox.message_view_class", ""),
                loader, errors);
        if (view != null) {
            String adapterField = schema.string(
                    "symbols.inbox.messages_view_adapter_field", "");
            if (declaredFieldInHierarchy(view, adapterField) == null) {
                errors.add("messages_view_adapter_field " + adapterField + " missing");
            }
        }
        // Optional: the OA follow chain is a secondary signal; topOut and the native
        // category already discriminate OA on device, so a stale manager name is advisory.
        Class<?> manager = tryLoad(schema.string("symbols.inbox.friend_manager_class", ""),
                loader);
        if (manager != null) {
            String instance = schema.string(
                    "symbols.inbox.friend_manager_instance_method", "");
            Method accessor = declaredMethodInHierarchy(manager, instance);
            if (accessor == null || accessor.getParameterTypes().length != 0
                    || !Modifier.isStatic(accessor.getModifiers())) {
                errors.add(manager.getName() + "#" + instance + " static accessor missing");
            }
            List<String> follows = schema.strings(
                    "symbols.inbox.friend_manager_follow_methods");
            if (follows.isEmpty()) {
                errors.add("friend_manager_follow_methods empty");
            }
            boolean anyFollow = false;
            for (String follow : follows) {
                Method check = declaredMethodInHierarchy(manager, follow);
                if (check != null && check.getParameterTypes().length == 1
                        && check.getParameterTypes()[0] == String.class
                        && check.getReturnType() == Boolean.TYPE) {
                    anyFollow = true;
                }
            }
            if (!follows.isEmpty() && !anyFollow) {
                errors.add(manager.getName() + " has none of " + follows + " (String)Z");
            }
        }
        Class<?> conversation = load(DexKitInboxFingerprint.CONVERSATION_CLASS, loader, errors);
        if (conversation != null) {
            String topOut = schema.string("symbols.inbox.top_out_field", "");
            Field marker = declaredFieldInHierarchy(conversation, topOut);
            if (marker == null) {
                errors.add("top_out_field " + topOut + " missing");
            } else {
                String valueField = schema.string("symbols.inbox.top_out_value_field", "");
                Field value = declaredFieldInHierarchy(marker.getType(), valueField);
                if (value == null || value.getType() != Integer.TYPE) {
                    errors.add(marker.getType().getName() + "#" + valueField
                            + " int value missing");
                }
            }
        }
        load(schema.string("symbols.inbox.biz_box_item_class", ""), loader, errors);
        // Optional: Strangers opens Zalo's native screen, so its box class is not required.
        tryLoad(schema.string("symbols.inbox.stranger_box_item_class", ""), loader);
        return errors.isEmpty();
    }

    /**
     * Bottom-tab auxiliary symbols, stable-shape only (T2a): the pager field by
     * type/setter shape and the home hook by the onPageSelected page callback.
     * The per-release letters are retired from code and schema. Advisory only —
     * the tab state and consumers arm from the DexKit overlay and runtime
     * discovery; a missing stable shape surfaces as a stale row, never disabling
     * working tabs.
     */
    private static boolean checkBottomTabsSymbols(SymbolSchema.Active schema, ClassLoader loader,
                                                  List<String> errors) {
        Class<?> view = load(schema.string("symbols.bottom_tabs.main_tab_view_class", ""),
                loader, errors);
        if (view != null) {
            if (!hasPagerShapedField(view)) {
                errors.add("MainTabView has no pager-shaped field");
            }
            if (declaredMethodInHierarchy(view, "onPageSelected") == null) {
                errors.add("MainTabView#onPageSelected missing");
            }
        }
        List<String> consumers = schema.strings("symbols.bottom_tabs.consumer_adapter_classes");
        if (consumers.isEmpty()) {
            errors.add("consumer_adapter_classes empty");
        }
        for (String adapter : consumers) {
            load(adapter, loader, errors);
        }
        List<String> states = schema.strings("symbols.bottom_tabs.current_state_classes");
        if (states.isEmpty()) {
            errors.add("current_state_classes empty");
        }
        for (String state : states) {
            load(state, loader, errors);
        }
        return errors.isEmpty();
    }

    /**
     * Chat reaction long-press symbols. The stable reaction surfaces need no schema, but
     * when a profile names a long-press method and armed field they must have the hooked
     * shape (void no-arg method, boolean field) on the stable chat row; otherwise the
     * hook silently matches nothing. Empty names mean the profile leaves this to the
     * stable surfaces and pass vacuously.
     */
    private static boolean checkChatReaction(SymbolSchema.Active schema, ClassLoader loader,
                                             List<String> errors) {
        String methodName = schema.string("symbols.chat.reaction_long_press_method", "");
        String armedField = schema.string("symbols.chat.reaction_long_press_armed_field", "");
        if (methodName.isEmpty() && armedField.isEmpty()) {
            return true;
        }
        if (methodName.isEmpty() || armedField.isEmpty()) {
            errors.add("reaction long-press method/field half present");
            return false;
        }
        Class<?> row = load("com.zing.zalo.ui.chat.chatrow.ChatRow", loader, errors);
        if (row != null) {
            Method method = declaredMethodInHierarchy(row, methodName);
            if (method == null || method.getParameterTypes().length != 0
                    || method.getReturnType() != Void.TYPE) {
                errors.add("reaction_long_press_method " + methodName + " not void()");
            }
            Field field = declaredFieldInHierarchy(row, armedField);
            if (field == null || field.getType() != Boolean.TYPE) {
                errors.add("reaction_long_press_armed_field " + armedField + " not boolean");
            }
        }
        return errors.isEmpty();
    }

    /**
     * Zinstant helper classes and method-name lists. The class names are stable
     * boundaries; the method lists carry hardcoded fallbacks in the feature, so a
     * drifted letter degrades to the fallback instead of arming the wrong suppression.
     * Validated here so drift surfaces with its reason; the feature keeps its own
     * per-list installed/stale reporting for the actual arming decision.
     */
    private static boolean checkZinstantSymbols(SymbolSchema.Active schema, ClassLoader loader,
                                                List<String> errors) {
        Class<?> communicator = load(
                schema.string("symbols.zinstant.communicator_class",
                        "com.zing.zalo.zinstant.utils.ZinstantCommunicatorHelper"),
                loader, errors);
        Class<?> scriptHelper = load(
                schema.string("symbols.zinstant.script_helper_class",
                        "com.zing.zalo.zinstant.utils.ScriptHelperImpl"),
                loader, errors);
        checkNamedMethods(communicator,
                schema.strings("symbols.zinstant.network_methods"), errors);
        checkNamedMethods(scriptHelper,
                schema.strings("symbols.zinstant.script_void_methods"), errors);
        checkNamedMethods(scriptHelper,
                schema.strings("symbols.zinstant.script_object_methods"), errors);
        return errors.isEmpty();
    }

    /**
     * A suppression list is a set of names to hook, matched by name only. Requiring every
     * listed name to exist would fail a family whose host moved one method to another
     * helper (260901903 moved get/post/requestSocket off the communicator); the family is
     * still armed when at least one listed name survives.
     */
    private static void checkNamedMethods(Class<?> owner, List<String> names,
                                          List<String> errors) {
        if (owner == null || names.isEmpty()) {
            return;
        }
        boolean any = false;
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            any |= declaredMethodInHierarchy(owner, name) != null;
        }
        if (!any) {
            errors.add(owner.getName() + " has none of " + names);
        }
    }

    /**
     * Live validation for a DexKit-resolved call callback: it must be a subclass of the
     * stable ZRTC base and declare at least one observed lifecycle callback. Without this
     * the overlay could arm the base class, whose hooks never fire because the registered
     * subclass overrides them.
     */
    static boolean checkDexkitCallCallback(ClassLoader loader, String className,
                                           List<String> errors) {
        if (className == null || className.isEmpty()) {
            errors.add("call callback class missing");
            return false;
        }
        try {
            Class<?> type = Class.forName(className, false, loader);
            Class<?> base = Class.forName(DexKitCallFingerprint.CALLBACK_BASE, false, loader);
            if (!base.isAssignableFrom(type) || type == base) {
                errors.add(className + " is not a CallCallback subclass");
                return false;
            }
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers())
                        && CallRecordingLifecycle.observes(method.getName())) {
                    return true;
                }
            }
            errors.add(className + " overrides no observed callback");
            return false;
        } catch (Throwable throwable) {
            errors.add(familyError("call_callback", throwable));
            return false;
        }
    }

    /**
     * Live validation for a DexKit-resolved chat expiry state: the enum declares both
     * states and the classifier is a static method returning the enum. Without this the
     * overlay could arm a rewrite on an unrelated state machine.
     */
    static boolean checkDexkitMediaState(ClassLoader loader, String stateClass,
                                        String classifierClass, String classifierMethod,
                                        List<String> errors) {
        if (stateClass == null || stateClass.isEmpty() || classifierClass == null
                || classifierClass.isEmpty() || classifierMethod == null
                || classifierMethod.isEmpty()) {
            errors.add("media state anchors incomplete");
            return false;
        }
        try {
            Class<?> state = Class.forName(stateClass, false, loader);
            if (!state.isEnum()) {
                errors.add(stateClass + " is not an enum");
                return false;
            }
            boolean expired = false;
            boolean fresh = false;
            for (java.lang.reflect.Field field : state.getDeclaredFields()) {
                if (!field.isEnumConstant()) {
                    continue;
                }
                if ("BIG_FILE_EXPIRED".equals(field.getName())) {
                    expired = true;
                } else if ("BIG_FILE_NOT_EXPIRED".equals(field.getName())) {
                    fresh = true;
                }
            }
            if (!expired || !fresh) {
                errors.add(stateClass + " declares no expiry states");
                return false;
            }
            Class<?> owner = Class.forName(classifierClass, false, loader);
            Method classifier = declaredMethodInHierarchy(owner, classifierMethod);
            if (classifier == null
                    || !Modifier.isStatic(classifier.getModifiers())
                    || classifier.getReturnType() != state) {
                errors.add(classifierClass + "#" + classifierMethod
                        + " static state classifier missing");
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            errors.add(familyError("media_state", throwable));
            return false;
        }
    }
    static boolean checkDexkitCallPeer(ClassLoader loader, String className, String accessor,
                                       String containerField, String handleField,
                                       List<String> errors) {
        if (className == null || className.isEmpty() || accessor == null || accessor.isEmpty()
                || containerField == null || containerField.isEmpty()
                || handleField == null || handleField.isEmpty()) {
            errors.add("call peer anchors incomplete");
            return false;
        }
        try {
            Class<?> manager = Class.forName(className, false, loader);
            Method instance = declaredMethodInHierarchy(manager, accessor);
            if (instance == null || instance.getParameterTypes().length != 0
                    || !Modifier.isStatic(instance.getModifiers())
                    || instance.getReturnType() != manager) {
                errors.add(className + "#" + accessor + " static self accessor missing");
                return false;
            }
            Field container = declaredFieldInHierarchy(manager, containerField);
            if (container == null || Modifier.isStatic(container.getModifiers())) {
                errors.add(className + "#" + containerField + " container missing");
                return false;
            }
            Field handle = declaredFieldInHierarchy(container.getType(), handleField);
            if (handle == null || handle.getType() != Long.TYPE) {
                errors.add(container.getType().getName() + "#" + handleField
                        + " long handle missing");
                return false;
            }
            return true;
        } catch (Throwable throwable) {
            errors.add(familyError("call_peer", throwable));
            return false;
        }
    }

    private static Method declaredMethodInHierarchy(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty()) {
            return null;
        }
        for (Class<?> type = owner; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (name.equals(method.getName())) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Field declaredFieldInHierarchy(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty()) {
            return null;
        }
        for (Class<?> type = owner; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (name.equals(field.getName())) {
                    return field;
                }
            }
        }
        return null;
    }

    /**
     * Pager field by stable shape: a MainTabView instance field typed as the
     * swipeable pager (ViewPagerCustomSwipeable), or whose type carries
     * setCurrentItem. The obfuscated field letter shuffles every release
     * (L0/K0/J0); the type and the setter are the stable substitutes the
     * force-home runtime already falls back to.
     */
    private static boolean hasPagerShapedField(Class<?> view) {
        if (view == null) {
            return false;
        }
        for (Class<?> type = view; type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType == null) {
                    continue;
                }
                if (fieldType.getName().endsWith("ViewPagerCustomSwipeable")) {
                    return true;
                }
                for (Method method : fieldType.getMethods()) {
                    if ("setCurrentItem".equals(method.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean checkPasscodeGrace(SymbolSchema.Active schema, ClassLoader loader,
                                              List<String> errors) {
        // Version drift rule: resolve the reader per profile as the static (I,String,Z)I invoked
        // immediately after const-string "SaveActiveTimePasscodeSetting", not by remembered name.
        // The setter is preflight-only and never hooked by any feature, so a missing setter
        // does not block the reader (reader-only arming); the setter anchors stay in the
        // DexKit vocabulary for future resolution.
        String prefKey = schema.string("symbols.passcode.active_time_pref_key", "");
        if (!"SaveActiveTimePasscodeSetting".equals(prefKey)) {
            errors.add("passcode preference key changed");
        }
        Class<?> reader = load(schema.string("symbols.passcode.prefs_int_reader_class", ""),
                loader, errors);
        if (reader != null) {
            staticMethodExact(reader,
                    schema.string("symbols.passcode.prefs_int_reader_method", ""),
                    Integer.TYPE, errors, Integer.TYPE, String.class, Boolean.TYPE);
        }
        return errors.isEmpty();
    }

    /**
     * DexKit Me-builder gate: the resolved builder exists on the stable TabMeView
     * with one parameter and a List return. Item classes, fields, and ids are
     * runtime-derived by the feature, never resolved; the hook's list-product
     * observation plus marker matching are the remaining semantic checks.
     */
    static boolean checkDexkitMeBuilder(ClassLoader loader, String builderMethod,
                                        List<String> errors) {
        Class<?> tabMe;
        try {
            tabMe = load("com.zing.zalo.ui.maintab.me.TabMeView", loader, errors);
        } catch (Throwable throwable) {
            errors.add("TabMeView missing");
            return false;
        }
        if (tabMe == null) {
            return false;
        }
        if (builderMethod == null || builderMethod.isEmpty()) {
            errors.add("TabMeView builder name missing");
            return false;
        }
        int matches = 0;
        for (Method method : tabMe.getDeclaredMethods()) {
            if (!builderMethod.equals(method.getName())) {
                continue;
            }
            if (method.getParameterTypes().length == 1
                    && List.class.isAssignableFrom(method.getReturnType())) {
                matches++;
            }
        }
        if (matches == 0) {
            errors.add("TabMeView#" + builderMethod + " builder shape changed");
        }
        return errors.isEmpty();
    }

    /**
     * DexKit inbox-category gate: the resolved category field is an int on the
     * stable Conversation class. The normal item class, adapter, row methods, and
     * box classes are runtime-derived and validated at hook time, never here.
     */
    static boolean checkDexkitInboxCategory(ClassLoader loader, String categoryField,
                                            List<String> errors) {
        Class<?> conversation = load(
                "com.zing.zalo.data.chat.model.tabmessage.Conversation", loader, errors);
        if (conversation != null) {
            field(conversation, categoryField, Integer.TYPE, errors);
        }
        return errors.isEmpty();
    }

    /**
     * DexKit reader-only passcode gate: the exact static {@code (int, String, boolean)
     * -> int} shape on the resolved reader class. Used when the setter is not
     * resolvable on the installed artifact. The setter is preflight-only (never
     * hooked); hook safety rests on the reader's key-bound uniqueness plus the live
     * shape check here plus the hook's runtime key-equality guard.
     */
    static boolean checkDexkitPasscodeReader(ClassLoader loader, String readerClass,
                                             String readerMethod, List<String> errors) {
        Class<?> reader = load(readerClass, loader, errors);
        if (reader != null) {
            staticMethodExact(reader, readerMethod,
                    Integer.TYPE, errors, Integer.TYPE, String.class, Boolean.TYPE);
        }
        return errors.isEmpty();
    }

    /**
     * Telemetry analytics-DAO gate: the four zero-arg accessors exist on the Room
     * generated database with DAO-shaped (non-void, non-primitive, non-framework)
     * returns. The event/screen/session/view mapping itself is DexKit-resolved via
     * table linkage; this gate proves the accessors exist with hookable shapes.
     */
    static boolean checkTelemetry(SymbolSchema.Active schema, ClassLoader loader,
                                  List<String> errors) {
        Class<?> database = load(schema.string("symbols.telemetry.analytics_db_class", ""),
                loader, errors);
        if (database == null) {
            return false;
        }
        String[] accessors = {
                schema.string("symbols.telemetry.analytics_event_accessor", ""),
                schema.string("symbols.telemetry.analytics_screen_accessor", ""),
                schema.string("symbols.telemetry.analytics_session_accessor", ""),
                schema.string("symbols.telemetry.analytics_view_accessor", "")};
        for (String name : accessors) {
            if (name == null || name.isEmpty()) {
                errors.add(database.getName() + " accessor name missing");
                continue;
            }
            try {
                Method method = database.getDeclaredMethod(name);
                Class<?> returns = method.getReturnType();
                if (returns == Void.TYPE || returns.isPrimitive()
                        || returns.getName().startsWith("java.")
                        || returns.getName().startsWith("android.")
                        || returns.getName().startsWith("androidx.")) {
                    errors.add(database.getName() + "#" + name + " accessor shape changed");
                }
            } catch (Throwable throwable) {
                errors.add(database.getName() + "#" + name + " accessor missing");
            }
        }
        return errors.isEmpty();
    }

    private static boolean checkBackupScheduled(SymbolSchema.Active schema, ClassLoader loader,
                                                List<String> errors) {
        Class<?> owner = load(schema.string("symbols.backup.interval_reader_class", ""),
                loader, errors);
        if (owner != null) {
            String name = schema.string("symbols.backup.interval_reader_method", "");
            try {
                Method method = owner.getDeclaredMethod(name,
                        Long.TYPE, Boolean.TYPE, String.class);
                if (method.getReturnType() != Long.TYPE || !Modifier.isStatic(method.getModifiers())) {
                    errors.add(owner.getName() + "#" + name + " shape changed");
                }
            } catch (Throwable throwable) {
                errors.add(owner.getName() + "#" + name + " signature changed");
            }
        }
        return errors.isEmpty();
    }

    /**
     * The webview anchors have two different owners: the open dispatch lives on the extracted
     * Kotlin companion sibling, the redirect transform on {@code ZaloWebView} itself. Both
     * re-obfuscate per release; the first and last dispatch parameters are interfaces whose names
     * rotate, so only their positions are pinned and the middle four types carry the shape.
     */
    private static boolean checkWebviewExternalize(SymbolSchema.Active schema, ClassLoader loader,
                                                   List<String> errors) {
        Class<?> webView = load(schema.string("symbols.webview.zalo_web_view_class", ""),
                loader, errors);
        Class<?> companion = load(schema.string("symbols.webview.companion_class", ""),
                loader, errors);
        if (webView != null) {
            String transform = schema.string("symbols.webview.redirect_transform_method", "");
            try {
                Method method = webView.getDeclaredMethod(transform, android.net.Uri.class);
                if (method.getReturnType() != android.net.Uri.class
                        || !Modifier.isStatic(method.getModifiers())) {
                    errors.add(webView.getName() + "#" + transform + " shape changed");
                }
            } catch (Throwable throwable) {
                errors.add(webView.getName() + "#" + transform + " signature changed");
            }
        }
        if (companion != null) {
            String dispatch = schema.string("symbols.webview.open_dispatch_method", "");
            int matches = 0;
            for (Method method : companion.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (dispatch.equals(method.getName())
                        && method.getReturnType() == Void.TYPE
                        && Modifier.isStatic(method.getModifiers())
                        && parameters.length == 6
                        && parameters[0].isInterface()
                        && parameters[1] == String.class
                        && parameters[2] == android.os.Bundle.class
                        && parameters[3] == Boolean.TYPE
                        && parameters[4] == Integer.TYPE
                        && parameters[5].isInterface()) {
                    matches++;
                }
            }
            if (matches != 1) {
                errors.add(companion.getName() + "#" + dispatch
                        + " expected one matching method, found " + matches);
            }
        }
        return errors.isEmpty();
    }

    private static Class<?> load(String name, ClassLoader loader, List<String> errors) {
        if (name == null || name.isEmpty()) {
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
        if (name == null || name.isEmpty()) {
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
        if (name == null || name.isEmpty()) {
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
        if (name == null || name.isEmpty()) {
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
        if (name == null || name.isEmpty()) {
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

    private static void staticMethodExact(Class<?> owner, String name, Class<?> returnType,
                                          List<String> errors, Class<?>... parameterTypes) {
        if (name == null || name.isEmpty()) {
            errors.add(owner.getName() + " method name missing");
            return;
        }
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            if (method.getReturnType() != returnType
                    || !Modifier.isStatic(method.getModifiers())) {
                errors.add(owner.getName() + "#" + name + " static signature changed");
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
        boolean passcodeGrace;
        boolean backupScheduled;
        boolean webviewExternalize;
        boolean telemetryDao;
        boolean callRecording;
        boolean inboxRows;
        boolean bottomTabsSymbols;
        boolean chatReaction;
        boolean zinstantSymbols;
        final List<String> inboxMediaErrors = new ArrayList<>();
        final List<String> inboxCategoryErrors = new ArrayList<>();
        final List<String> meErrors = new ArrayList<>();
        final List<String> bottomErrors = new ArrayList<>();
        final List<String> zinstantMessageErrors = new ArrayList<>();
        final List<String> zinstantFeedErrors = new ArrayList<>();
        final List<String> statusPrivacyErrors = new ArrayList<>();
        final List<String> passcodeGraceErrors = new ArrayList<>();
        final List<String> backupScheduledErrors = new ArrayList<>();
        final List<String> webviewErrors = new ArrayList<>();
        final List<String> telemetryDaoErrors = new ArrayList<>();
        final List<String> inboxRowsErrors = new ArrayList<>();
        final List<String> bottomTabsSymbolsErrors = new ArrayList<>();
        final List<String> chatReactionErrors = new ArrayList<>();
        final List<String> zinstantSymbolsErrors = new ArrayList<>();
        final List<String> callRecordingErrors = new ArrayList<>();

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
            if (passcodeGrace) count++;
            if (backupScheduled) count++;
            if (webviewExternalize) count++;
            if (telemetryDao) count++;
            if (callRecording) count++;
            if (inboxRows) count++;
            if (bottomTabsSymbols) count++;
            if (chatReaction) count++;
            if (zinstantSymbols) count++;
            return count;
        }

        int total() {
            return 16;
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
            append(value, "passcode_grace", passcodeGrace);
            append(value, "backup_scheduled", backupScheduled);
            append(value, "webview_externalize", webviewExternalize);
            append(value, "telemetry_dao", telemetryDao);
            append(value, "call_recording", callRecording);
            append(value, "inbox_rows", inboxRows);
            append(value, "bottom_tabs_symbols", bottomTabsSymbols);
            append(value, "chat_reaction", chatReaction);
            append(value, "zinstant_symbols", zinstantSymbols);
            return value.toString();
        }

        private static void append(StringBuilder value, String name, boolean resolved) {
            if (value.length() > 0) value.append(' ');
            value.append(name).append('=').append(resolved ? "ok" : "no");
        }
    }
}
