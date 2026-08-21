package com.ez.zalopatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** User-facing registry of the primary runtime hook path behind each tweak. */
final class TweakHookInfo {
    static final class Info {
        final String path;
        final boolean driftProne;
        final List<String> driftSymbols;

        Info(String path, List<String> driftSymbols) {
            this.path = path;
            this.driftSymbols = Collections.unmodifiableList(driftSymbols);
            this.driftProne = !driftSymbols.isEmpty();
        }
    }

    private TweakHookInfo() {
    }

    static Info forKey(String key, SymbolSchema.Active schema) {
        String path;
        switch (key) {
            case Tweaks.KEY_HIDE_DISCOVERY_TAB:
                path = bottomState(schema) + "#p()/q()/h()/a()/e()/m()";
                break;
            case Tweaks.KEY_HIDE_TIMELINE_TAB:
                path = bottomState(schema) + "#p()/q()/h()/n()/m()";
                break;
            case Tweaks.KEY_KEEP_GROUP_TAB:
                path = bottomState(schema) + "#p()/q()/h()/b()/f()/m()";
                break;
            case Tweaks.KEY_FORCE_MESSAGES_AS_HOME:
                path = method(schema, "symbols.bottom_tabs.main_tab_view_class",
                        "com.zing.zalo.ui.maintab.MainTabView",
                        "symbols.bottom_tabs.main_tab_home_hook_method", "<lifecycle>")
                        + " -> ViewPager#setCurrentItem()";
                break;
            case Tweaks.KEY_HIDE_QR_WALLET:
            case Tweaks.KEY_HIDE_ZCLOUD:
            case Tweaks.KEY_HIDE_ZSTYLE:
            case Tweaks.KEY_HIDE_ZBUSINESS:
                path = method(schema, "symbols.me.tab_me_class",
                        "com.zing.zalo.ui.maintab.me.TabMeView",
                        "symbols.me.current_builder_method", "<builder>")
                        + " + TextView#setText()";
                break;
            case Tweaks.KEY_DISABLE_EVENT_ANALYTICS:
                path = analyticsMethod(schema, "analytics_event_accessor");
                break;
            case Tweaks.KEY_DISABLE_SCREEN_ANALYTICS:
                path = analyticsMethod(schema, "analytics_screen_accessor");
                break;
            case Tweaks.KEY_DISABLE_SESSION_ANALYTICS:
                path = analyticsMethod(schema, "analytics_session_accessor");
                break;
            case Tweaks.KEY_DISABLE_VIEW_ANALYTICS:
                path = analyticsMethod(schema, "analytics_view_accessor");
                break;
            case Tweaks.KEY_DISABLE_CRASHLYTICS:
                path = "FirebaseCrashlytics#recordException()/log()/setCustomKey()";
                break;
            case Tweaks.KEY_DISABLE_AD_ID:
                path = "AdvertisingIdClient#getAdvertisingIdInfo()/getInfo()";
                break;
            case Tweaks.KEY_DISABLE_MEASUREMENT_BIND:
                path = "ContextImpl/ContextWrapper#*Service(Intent)";
                break;
            case Tweaks.KEY_HIDE_MEDIA_BOX:
                path = inboxListSetter(schema);
                break;
            case Tweaks.KEY_HIDE_ZCLOUD_BANNER:
                path = "XResources#hookLayout(messageslist/fixed_banner_container)";
                break;
            case Tweaks.KEY_FILTER_POPOVER_CATEGORIES:
                path = "RecyclerView#setAdapter() + " + inboxListSetter(schema);
                break;
            case Tweaks.KEY_CATEGORY_GROUPS:
                path = inboxListSetter(schema) + " + "
                        + schema.string("symbols.inbox.normal_item_class", "<conversation row>")
                        + "#" + schema.string("symbols.inbox.row_uid_method", "<uid>") + "()/"
                        + schema.string("symbols.inbox.group_flag_method", "<group>") + "()";
                break;
            case Tweaks.KEY_CATEGORY_STRANGERS:
                path = inboxListSetter(schema) + " + "
                        + schema.string("symbols.inbox.stranger_box_item_class", "<stranger row>");
                break;
            case Tweaks.KEY_CATEGORY_OA:
                path = inboxListSetter(schema) + " + "
                        + schema.string("symbols.inbox.friend_manager_class", "<follow manager>")
                        + "#" + schema.string("symbols.inbox.friend_manager_instance_method", "<instance>")
                        + "()/" + methods(schema.strings("symbols.inbox.friend_manager_follow_methods"));
                break;
            case Tweaks.KEY_HIDE_REACTION_ROW:
                path = "View#performLongClick() + PopupWindow/Dialog#show()";
                break;
            case Tweaks.KEY_BLOCK_SEEN_STATUS:
                path = schema.string("symbols.chat.send_seen_manager_class", "<seen manager>")
                        + "#" + schema.string("symbols.chat.send_seen_single_method", "<enqueue>")
                        + "()/" + schema.string("symbols.chat.send_seen_batch_method", "<enqueue batch>")
                        + "() + "
                        + schema.string("symbols.chat.message_repository_class", "<message repository>")
                        + "#" + schema.string("symbols.chat.send_ack_method", "<send ack>") + "()";
                break;
            case Tweaks.KEY_BLOCK_TYPING_STATUS:
                path = schema.string("symbols.chat.message_repository_class", "<message repository>")
                        + "#" + schema.string("symbols.chat.send_typing_method", "<send typing>")
                        + "()";
                break;
            case Tweaks.KEY_AUTO_RECORD_CALLS:
                path = "PeerJNI#zrtc_peer_start_record_audio() + CallCallback callbacks";
                break;
            case Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS:
                path = "CallRecordingNotificationReceiver#onReceive()";
                break;
            case Tweaks.KEY_CALL_RECORDING_PROBE:
                path = "PeerJNI/CallCallback callbacks + ZmInCallActivity lifecycle";
                break;
            case Tweaks.KEY_BACKUP_FREQUENT_PUSH:
                path = method(schema, "symbols.backup.interval_reader_class",
                        "<Zalo preference helper>",
                        "symbols.backup.interval_reader_method", "<long read>");
                break;
            case Tweaks.KEY_HIDE_MESSAGE_ADS:
                path = method(schema, "symbols.zinstant.ad_item_view_class",
                        "com.zing.zalo.ui.widget.ZinstantAdItemView",
                        "symbols.zinstant.ad_bind_method", "<bind>")
                        + " + Activity#onResume()";
                break;
            case Tweaks.KEY_HIDE_FEED_ADS:
                path = method(schema, "symbols.zinstant.feed_ads_class",
                        "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds",
                        "symbols.zinstant.feed_bind_method", "<bind>")
                        + " + Activity#onResume()";
                break;
            case Tweaks.KEY_HIDE_PROMO_SERVICES:
                path = "ZinstantCommunicatorHelper#sendHttpRequest()/get()/post()"
                        + " + ScriptHelperImpl#downloadScripts()";
                break;
            case Tweaks.KEY_HIDE_PROMO_NOTIFICATIONS:
            case Tweaks.KEY_RECORD_NOTIFICATION_HISTORY:
                path = "NotificationManager#notify*()";
                break;
            default:
                path = "No runtime hook";
                break;
        }
        return new Info(path, driftSymbols(key, schema));
    }

    private static String analyticsMethod(SymbolSchema.Active schema, String accessor) {
        return method(schema, "symbols.telemetry.analytics_db_class",
                "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl",
                "symbols.telemetry." + accessor, "<DAO accessor>");
    }

    private static String inboxListSetter(SymbolSchema.Active schema) {
        return schema.string("symbols.inbox.message_adapter_class", "<message adapter>")
                + "#<List setter>(List)";
    }

    private static String bottomState(SymbolSchema.Active schema) {
        List<String> classes = schema.strings("symbols.bottom_tabs.current_state_classes");
        return classes.isEmpty()
                ? schema.string("symbols.bottom_tabs.legacy_state_class", "<bottom-tab state>")
                : classes.get(0);
    }

    private static String method(SymbolSchema.Active schema, String classPath, String fallbackClass,
                                 String methodPath, String fallbackMethod) {
        return schema.string(classPath, fallbackClass) + "#"
                + schema.string(methodPath, fallbackMethod) + "()";
    }

    private static String methods(List<String> names) {
        if (names.isEmpty()) {
            return "<follow check>()";
        }
        StringBuilder value = new StringBuilder();
        for (String name : names) {
            if (value.length() > 0) {
                value.append('/');
            }
            value.append(name).append("()");
        }
        return value.toString();
    }

    private static List<String> driftSymbols(String key, SymbolSchema.Active schema) {
        switch (key) {
            case Tweaks.KEY_HIDE_DISCOVERY_TAB:
                return Arrays.asList(bottomState(schema), "#p()", "q()", "h()", "a()", "e()", "m()");
            case Tweaks.KEY_HIDE_TIMELINE_TAB:
                return Arrays.asList(bottomState(schema), "#p()", "q()", "h()", "n()", "m()");
            case Tweaks.KEY_KEEP_GROUP_TAB:
                return Arrays.asList(bottomState(schema), "#p()", "q()", "h()", "b()", "f()", "m()");
            case Tweaks.KEY_FORCE_MESSAGES_AS_HOME:
                return Collections.singletonList("#" + schema.string(
                        "symbols.bottom_tabs.main_tab_home_hook_method", "<lifecycle>") + "()");
            case Tweaks.KEY_HIDE_QR_WALLET:
            case Tweaks.KEY_HIDE_ZCLOUD:
            case Tweaks.KEY_HIDE_ZSTYLE:
            case Tweaks.KEY_HIDE_ZBUSINESS:
                return Collections.singletonList("#" + schema.string(
                        "symbols.me.current_builder_method", "<builder>") + "()");
            case Tweaks.KEY_DISABLE_EVENT_ANALYTICS:
                return analyticsAccessorSymbol(schema, "analytics_event_accessor");
            case Tweaks.KEY_DISABLE_SCREEN_ANALYTICS:
                return analyticsAccessorSymbol(schema, "analytics_screen_accessor");
            case Tweaks.KEY_DISABLE_SESSION_ANALYTICS:
                return analyticsAccessorSymbol(schema, "analytics_session_accessor");
            case Tweaks.KEY_DISABLE_VIEW_ANALYTICS:
                return analyticsAccessorSymbol(schema, "analytics_view_accessor");
            case Tweaks.KEY_HIDE_MEDIA_BOX:
            case Tweaks.KEY_FILTER_POPOVER_CATEGORIES:
                return Collections.singletonList(schema.string(
                        "symbols.inbox.message_adapter_class", "<message adapter>"));
            case Tweaks.KEY_CATEGORY_GROUPS:
                return Arrays.asList(
                        schema.string("symbols.inbox.message_adapter_class", "<message adapter>"),
                        schema.string("symbols.inbox.normal_item_class", "<conversation row>"),
                        "#" + schema.string("symbols.inbox.row_uid_method", "<uid>") + "()",
                        schema.string("symbols.inbox.group_flag_method", "<group>") + "()");
            case Tweaks.KEY_CATEGORY_STRANGERS:
                return Arrays.asList(
                        schema.string("symbols.inbox.message_adapter_class", "<message adapter>"),
                        schema.string("symbols.inbox.stranger_box_item_class", "<stranger row>"));
            case Tweaks.KEY_CATEGORY_OA:
                java.util.ArrayList<String> oa = new java.util.ArrayList<>();
                oa.add(schema.string("symbols.inbox.message_adapter_class", "<message adapter>"));
                oa.add(schema.string("symbols.inbox.friend_manager_class", "<follow manager>"));
                oa.add("#" + schema.string(
                        "symbols.inbox.friend_manager_instance_method", "<instance>") + "()");
                List<String> followMethods = schema.strings(
                        "symbols.inbox.friend_manager_follow_methods");
                if (followMethods.isEmpty()) {
                    oa.add("<follow check>()");
                } else {
                    for (String method : followMethods) {
                        oa.add(method + "()");
                    }
                }
                return oa;
            case Tweaks.KEY_HIDE_MESSAGE_ADS:
                return Collections.singletonList("#" + schema.string(
                        "symbols.zinstant.ad_bind_method", "<bind>") + "()");
            case Tweaks.KEY_HIDE_FEED_ADS:
                return Collections.singletonList("#" + schema.string(
                        "symbols.zinstant.feed_bind_method", "<bind>") + "()");
            case Tweaks.KEY_BLOCK_SEEN_STATUS:
                return Arrays.asList(
                        schema.string("symbols.chat.send_seen_manager_class", "<seen manager>"),
                        "#" + schema.string("symbols.chat.send_seen_single_method", "<enqueue>") + "()",
                        schema.string("symbols.chat.send_seen_batch_method", "<enqueue batch>") + "()",
                        schema.string("symbols.chat.message_repository_class", "<message repository>"),
                        "#" + schema.string("symbols.chat.send_ack_method", "<send ack>") + "()");
            case Tweaks.KEY_BLOCK_TYPING_STATUS:
                return Arrays.asList(
                        schema.string("symbols.chat.message_repository_class", "<message repository>"),
                        "#" + schema.string("symbols.chat.send_typing_method", "<send typing>") + "()");
            case Tweaks.KEY_BACKUP_FREQUENT_PUSH:
                return Arrays.asList(
                        schema.string("symbols.backup.interval_reader_class",
                                "<Zalo preference helper>"),
                        "#" + schema.string("symbols.backup.interval_reader_method",
                                "<long read>") + "()");
            default:
                return Collections.emptyList();
        }
    }

    private static List<String> analyticsAccessorSymbol(
            SymbolSchema.Active schema, String accessor) {
        return Collections.singletonList("#" + schema.string(
                "symbols.telemetry." + accessor, "<DAO accessor>") + "()");
    }
}
