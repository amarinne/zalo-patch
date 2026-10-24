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
                path = schema.string("symbols.bottom_tabs.main_tab_view_class",
                        "com.zing.zalo.ui.maintab.MainTabView")
                        + "#onPageSelected() -> ViewPager#setCurrentItem()";
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
                path = "LayoutInflater#inflate(messageslist/fixed_banner_container)";
                break;
            case Tweaks.KEY_FILTER_POPOVER_CATEGORIES:
                path = "RecyclerView#setAdapter() + " + inboxListSetter(schema);
                break;
            case Tweaks.KEY_CATEGORY_GROUPS:
                path = inboxListSetter(schema) + " + "
                        + schema.string("symbols.inbox.normal_item_class", "<conversation row>")
                        + "#" + schema.string("symbols.inbox.row_uid_method", "<uid>") + "()/group_ prefix";
                break;
            case Tweaks.KEY_CATEGORY_STRANGERS:
                path = "StrangerMessagesView native route";
                break;
            case Tweaks.KEY_CATEGORY_OA:
                path = inboxListSetter(schema) + " + topOut marker/native category";
                break;
            case Tweaks.KEY_HIDE_REACTION_ROW:
                path = "View#performLongClick() + PopupWindow/Dialog#show()";
                break;
            case Tweaks.KEY_CUSTOM_REACTION:
                String reactionClass = schema.string(
                        DexKitReactionSendFingerprint.ANCHOR_REACTION_CLASS, "<reaction>");
                path = DexKitReactionSendFingerprint.PICKER_CLASS + "#"
                        + DexKitReactionSendFingerprint.LISTENER_GETTER + "() -> "
                        + schema.string(DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS,
                        "<listener>")
                        + "#" + schema.string(DexKitReactionSendFingerprint.ANCHOR_CALLBACK_METHOD,
                        "<reaction callback>")
                        + "(" + reactionClass + ",String) + "
                        + reactionClass
                        + "#" + schema.string(DexKitReactionSendFingerprint.ANCHOR_FACTORY_METHOD,
                        "<factory>") + "(int,String)";
                break;
            case Tweaks.KEY_OPEN_LINKS_EXTERNALLY:
                path = schema.string("symbols.webview.companion_class", "<webview dispatcher>")
                        + "#" + schema.string("symbols.webview.open_dispatch_method", "<open dispatch>")
                        + "() + "
                        + schema.string("symbols.webview.zalo_web_view_class", "<webview>")
                        + "#" + schema.string(
                                "symbols.webview.redirect_transform_method", "<redirect>")
                        + "(Uri)";
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
            case Tweaks.KEY_VIEW_STORIES_ANONYMOUSLY:
                path = schema.string(DexKitStoryFingerprint.ANCHOR_SEEN_CLASS, "<story request>")
                        + "#" + schema.string(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD, "<seen>")
                        + "(int,String) + "
                        + schema.string(DexKitStoryFingerprint.ANCHOR_SUGGESTED_CLASS,
                                "<suggested story request>")
                        + "#" + schema.string(DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD,
                                "<suggested seen>") + "(int,int,String)";
                break;
            case Tweaks.KEY_PREFER_ORIGINAL_PHOTO:
                path = DexKitOriginalPhotoFingerprint.MEDIA_ITEM + "#"
                        + schema.string(DexKitOriginalPhotoFingerprint.APPLY, "<apply quality>")
                        + "(int) + " + DexKitOriginalPhotoFingerprint.SHARE_VIEW + "#"
                        + schema.string(DexKitOriginalPhotoFingerprint.SHARE, "<photo share>") + "(Bundle)";
                break;
            case Tweaks.KEY_KEEP_EXPIRED_MEDIA:
                path = schema.string("symbols.media.state_classifier_class", "<media classifier>")
                        + "#" + schema.string("symbols.media.state_classifier_method", "<classify>")
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
            case Tweaks.KEY_PASSCODE_GRACE:
            case Tweaks.KEY_PASSCODE_GRACE_MS:
                path = schema.string("symbols.passcode.prefs_int_reader_class",
                        "<Zalo int preference reader>") + "#"
                        + schema.string("symbols.passcode.prefs_int_reader_method",
                        "<int read>") + "(I,String,Z)I";
                break;
            case Tweaks.KEY_HIDE_MESSAGE_ADS:
                path = method(schema, "symbols.zinstant.ad_item_view_class",
                        "com.zing.zalo.ui.widget.ZinstantAdItemView",
                        "symbols.zinstant.ad_bind_method", "<bind>")
                        + " + Activity#onResume()";
                break;
            case Tweaks.KEY_HIDE_STORY_ADS:
                path = method(schema, DexKitStoryAdFingerprint.ANCHOR_CLASS,
                        "<story ad view>", DexKitStoryAdFingerprint.ANCHOR_BIND, "<bind>")
                        + " + constructors (after collapse)";
                break;
            case Tweaks.KEY_HIDE_FEED_ADS:
                path = method(schema, "symbols.zinstant.feed_ads_class",
                        "com.zing.zalo.social.presentation.timeline.components.ads.FeedItemZInstantAds",
                        "symbols.zinstant.feed_bind_method", "<bind>")
                        + " + Activity#onResume()";
                break;
            case Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS:
                String readerClass = schema.string(
                        "symbols.remote_config.int_reader_class", "");
                String readerMethod = schema.string(
                        "symbols.remote_config.int_reader_method", "");
                path = (readerClass.isEmpty() ? "DexKit keyed config reader" : readerClass)
                        + (readerMethod.isEmpty() ? "" : "#" + readerMethod)
                        + "(String,int)int";
                break;
            case Tweaks.KEY_HIDE_VIDEO_ADS:
                path = "Qualified Zalo Video ad roots: constructor and native bind after-hooks";
                break;
            case Tweaks.KEY_HIDE_PROMO_SERVICES:
                path = "ZinstantCommunicatorHelper#sendHttpRequest()/get()/post()"
                        + " + ScriptHelperImpl#downloadScripts()";
                break;
            case Tweaks.KEY_DISABLE_ADTIMA_OFFLINE_TRACKING:
                path = method(schema, DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS,
                        "<Adtima offline-window owner>",
                        DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD, "<offline window>")
                        + "Z + "
                        + method(schema, DexKitAdtimaFingerprint.TRACKING_GATE_CLASS,
                                "<Adtima tracking owner>",
                                DexKitAdtimaFingerprint.TRACKING_GATE_METHOD, "<tracking gate>")
                        + "Z";
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
        return classes.isEmpty() ? "<bottom-tab state>" : classes.get(0);
    }

    private static String method(SymbolSchema.Active schema, String classPath, String fallbackClass,
                                 String methodPath, String fallbackMethod) {
        return schema.string(classPath, fallbackClass) + "#"
                + schema.string(methodPath, fallbackMethod) + "()";
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
                return Collections.singletonList("#onPageSelected()");
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
                        "group_ prefix");
            case Tweaks.KEY_CATEGORY_STRANGERS:
                return Collections.singletonList("StrangerMessagesView native route");
            case Tweaks.KEY_CATEGORY_OA:
                return Arrays.asList(
                        schema.string("symbols.inbox.message_adapter_class", "<message adapter>"),
                        "topOut marker",
                        "native category");
            case Tweaks.KEY_HIDE_MESSAGE_ADS:
                return Collections.singletonList("#" + schema.string(
                        "symbols.zinstant.ad_bind_method", "<bind>") + "()");
            case Tweaks.KEY_HIDE_STORY_ADS:
                return Arrays.asList(schema.string(DexKitStoryAdFingerprint.ANCHOR_CLASS,
                        "<story ad view>"), "#" + schema.string(
                        DexKitStoryAdFingerprint.ANCHOR_BIND, "<bind>") + "()");
            case Tweaks.KEY_HIDE_FEED_ADS:
                return Collections.singletonList("#" + schema.string(
                        "symbols.zinstant.feed_bind_method", "<bind>") + "()");
            case Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS:
                List<String> readerSymbols = new java.util.ArrayList<>();
                String readerClass = schema.string(
                        "symbols.remote_config.int_reader_class", "");
                String readerMethod = schema.string(
                        "symbols.remote_config.int_reader_method", "");
                if (!readerClass.isEmpty()) readerSymbols.add(readerClass);
                if (!readerMethod.isEmpty()) readerSymbols.add("#" + readerMethod);
                return readerSymbols;
            case Tweaks.KEY_DISABLE_ADTIMA_OFFLINE_TRACKING:
                return Arrays.asList(
                        schema.string(DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS,
                                "<Adtima offline-window owner>"),
                        "#" + schema.string(DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD,
                                "<offline window>") + "()Z",
                        schema.string(DexKitAdtimaFingerprint.TRACKING_GATE_CLASS,
                                "<Adtima tracking owner>"),
                        "#" + schema.string(DexKitAdtimaFingerprint.TRACKING_GATE_METHOD,
                                "<tracking gate>") + "()Z");
            case Tweaks.KEY_HIDE_VIDEO_ADS:
                java.util.ArrayList<String> videoAnchors = new java.util.ArrayList<>();
                for (DexKitVideoAdsFingerprint.Route route : DexKitVideoAdsFingerprint.Route.values()) {
                    videoAnchors.add(schema.string(route.anchor("view_class"), "<" + route.id + ">"));
                    videoAnchors.add("#" + schema.string(route.anchor("marker_method"), "<marker>") + "()");
                }
                return videoAnchors;
            case Tweaks.KEY_CUSTOM_REACTION:
                String reactionClass = schema.string(
                        DexKitReactionSendFingerprint.ANCHOR_REACTION_CLASS, "<reaction>");
                return Arrays.asList(
                        DexKitReactionSendFingerprint.PICKER_CLASS,
                        schema.string(DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS,
                                "<listener>"),
                        "#" + schema.string(DexKitReactionSendFingerprint.ANCHOR_CALLBACK_METHOD,
                                "<reaction callback>") + "(" + reactionClass + ",String)",
                        reactionClass,
                        "#" + schema.string(DexKitReactionSendFingerprint.ANCHOR_FACTORY_METHOD,
                                "<factory>") + "(int,String)");
            case Tweaks.KEY_OPEN_LINKS_EXTERNALLY:
                return Arrays.asList(
                        schema.string("symbols.webview.companion_class", "<webview dispatcher>"),
                        "#" + schema.string("symbols.webview.open_dispatch_method",
                                "<open dispatch>") + "()",
                        schema.string("symbols.webview.zalo_web_view_class", "<webview>"),
                        "#" + schema.string("symbols.webview.redirect_transform_method",
                                "<redirect>") + "(Uri)");
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
            case Tweaks.KEY_VIEW_STORIES_ANONYMOUSLY:
                return Arrays.asList(
                        schema.string(DexKitStoryFingerprint.ANCHOR_SEEN_CLASS, "<story request>"),
                        "#" + schema.string(DexKitStoryFingerprint.ANCHOR_SEEN_METHOD,
                                "<seen>") + "(int,String)",
                        schema.string(DexKitStoryFingerprint.ANCHOR_SUGGESTED_CLASS,
                                "<suggested story request>"),
                        "#" + schema.string(DexKitStoryFingerprint.ANCHOR_SUGGESTED_METHOD,
                                "<suggested seen>") + "(int,int,String)");
            case Tweaks.KEY_PREFER_ORIGINAL_PHOTO:
                return Arrays.asList(
                        schema.string(DexKitOriginalPhotoFingerprint.PICKER, "<photo picker>"),
                        "#" + schema.string(DexKitOriginalPhotoFingerprint.GET_QUALITY, "<quality>") + "()",
                        "#" + schema.string(DexKitOriginalPhotoFingerprint.APPLY, "<apply quality>") + "(int)",
                        schema.string(DexKitOriginalPhotoFingerprint.OUTGOING, "<photo conversion>"),
                        "#" + schema.string(DexKitOriginalPhotoFingerprint.SHARE, "<photo share>") + "(Bundle)");
            case Tweaks.KEY_KEEP_EXPIRED_MEDIA:
                return Arrays.asList(
                        schema.string("symbols.media.state_classifier_class", "<media classifier>"),
                        "#" + schema.string("symbols.media.state_classifier_method",
                                "<classify>") + "()");
            case Tweaks.KEY_BACKUP_FREQUENT_PUSH:
                return Arrays.asList(
                        schema.string("symbols.backup.interval_reader_class",
                                "<Zalo preference helper>"),
                        "#" + schema.string("symbols.backup.interval_reader_method",
                                "<long read>") + "()");
            case Tweaks.KEY_PASSCODE_GRACE:
            case Tweaks.KEY_PASSCODE_GRACE_MS:
                return Arrays.asList(
                        schema.string("symbols.passcode.prefs_int_reader_class",
                                "<Zalo int preference reader>"),
                        "#" + schema.string("symbols.passcode.prefs_int_reader_method",
                                "<int read>") + "(I,String,Z)I");
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
