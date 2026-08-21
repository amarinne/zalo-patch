package com.ez.zalopatch;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class Tweaks {
    public enum Requirement {
        NONE,
        ROOT,
        RESOURCE_HOOKS
    }
    /**
     * The only preference file the hooked Zalo process can read. It is opened world-readable and
     * mirrored to system properties for exactly that reason, so nothing private belongs in it.
     * Module-process-only state goes in {@link UiSettings#PREFS_NAME} or SettingsChanges instead;
     * the three files look similarly named and the separation is deliberate.
     */
    public static final String PREFS_NAME = "tweaks";
    public static final String KEY_PREFS_SCHEMA_VERSION = "internal.prefs_schema_version";
    public static final int PREFS_SCHEMA_VERSION = 7;

    // Stable identity keys. User-facing section names live in resources.
    public static final String SECTION_NAVIGATION = "navigation";
    public static final String SECTION_ME = "me";
    public static final String SECTION_TELEMETRY = "telemetry";
    public static final String SECTION_INBOX = "inbox";
    public static final String SECTION_CHAT = "chat";
    public static final String SECTION_CALLS = "calls";
    public static final String SECTION_ADS = "ads";
    public static final String SECTION_NOTIFICATIONS = "notifications";
    public static final String SECTION_BACKUP = "backup";
    public static final String SECTION_DEVELOPER = "developer";

    public static final String KEY_HIDE_DISCOVERY_TAB = "ui.hide_discovery_tab";
    public static final String KEY_HIDE_TIMELINE_TAB = "ui.hide_timeline_tab";
    public static final String KEY_HIDE_QR_WALLET = "me.hide_qr_wallet";
    public static final String KEY_HIDE_ZCLOUD = "me.hide_zcloud";
    public static final String KEY_HIDE_ZSTYLE = "me.hide_zstyle";
    public static final String KEY_HIDE_ZBUSINESS = "me.hide_zbusiness";
    public static final String KEY_DISABLE_EVENT_ANALYTICS = "telemetry.disable_event_analytics";
    public static final String KEY_DISABLE_SCREEN_ANALYTICS = "telemetry.disable_screen_analytics";
    public static final String KEY_DISABLE_SESSION_ANALYTICS = "telemetry.disable_session_analytics";
    public static final String KEY_DISABLE_VIEW_ANALYTICS = "telemetry.disable_view_analytics";
    public static final String KEY_DISABLE_CRASHLYTICS = "telemetry.disable_crashlytics";
    public static final String KEY_DISABLE_AD_ID = "telemetry.disable_ad_id";
    public static final String KEY_DISABLE_MEASUREMENT_BIND = "telemetry.disable_measurement_bind";

    public static final String KEY_HIDE_MEDIA_BOX = "inbox.hide_media_box";
    public static final String KEY_HIDE_ZCLOUD_BANNER = "inbox.hide_zcloud_banner";
    public static final String KEY_FILTER_POPOVER_CATEGORIES = "inbox.filter_popover";
    public static final String KEY_HIDE_REACTION_ROW = "messages.hide_reaction_row";
    public static final String KEY_BLOCK_SEEN_STATUS = "messages.block_seen_status";
    public static final String KEY_BLOCK_TYPING_STATUS = "messages.block_typing_status";
    public static final String KEY_KEEP_GROUP_TAB = "ui.keep_group_tab";
    public static final String KEY_FORCE_MESSAGES_AS_HOME = "ui.force_messages_as_home";
    public static final String KEY_HIDE_MESSAGE_ADS = "ads.hide_message_ads";
    public static final String KEY_HIDE_FEED_ADS = "ads.hide_feed_ads";
    public static final String KEY_HIDE_PROMO_SERVICES = "ads.hide_promo_services";
    public static final String KEY_HIDE_PROMO_NOTIFICATIONS = "ads.hide_promo_notifications";
    public static final String KEY_RECORD_NOTIFICATION_HISTORY = "notifications.record_history";
    public static final String KEY_NOTIFICATION_HISTORY_RETENTION = "notifications.history_retention";
    public static final String KEY_CATEGORY_GROUPS = "inbox.category.groups";
    public static final String KEY_CATEGORY_STRANGERS = "inbox.category.strangers";
    public static final String KEY_CATEGORY_OA = "inbox.category.oa";
    public static final String KEY_DEFAULT_INBOX_FILTER = "inbox.default_filter";
    public static final String KEY_AUTO_RECORD_CALLS = "calls.auto_record";
    public static final String KEY_CALL_RECORDING_NOTIFICATIONS = "calls.recording_notifications";
    public static final String KEY_CALL_RECORDING_PROBE = "calls.recording_probe";
    public static final String KEY_BACKUP_FREQUENT_PUSH = "backup.frequent_push";
    public static final String KEY_BACKUP_PUSH_INTERVAL = "backup.push_interval";

    public static final List<String> TELEMETRY_KEYS = Collections.unmodifiableList(Arrays.asList(
            KEY_DISABLE_EVENT_ANALYTICS,
            KEY_DISABLE_SCREEN_ANALYTICS,
            KEY_DISABLE_SESSION_ANALYTICS,
            KEY_DISABLE_VIEW_ANALYTICS,
            KEY_DISABLE_CRASHLYTICS,
            KEY_DISABLE_AD_ID,
            KEY_DISABLE_MEASUREMENT_BIND
    ));

    public static final class Group {
        public enum SpecialBlock {
            NONE,
            TELEMETRY,
            BOTTOM_TABS_STATUS,
            DEVELOPER_TOOLS
        }

        public final int titleRes;
        public final List<String> keys;
        public final SpecialBlock specialBlock;

        private Group(int titleRes, String... keys) {
            this(titleRes, SpecialBlock.NONE, keys);
        }

        private Group(int titleRes, SpecialBlock specialBlock, String... keys) {
            this.titleRes = titleRes;
            this.keys = Collections.unmodifiableList(Arrays.asList(keys));
            this.specialBlock = specialBlock;
        }
    }

    public static List<Group> groups(String section) {
        if (SECTION_NAVIGATION.equals(section)) {
            return Arrays.asList(
                    new Group(R.string.zp_group_bottom_tabs, Group.SpecialBlock.BOTTOM_TABS_STATUS,
                            KEY_HIDE_DISCOVERY_TAB,
                            KEY_HIDE_TIMELINE_TAB, KEY_KEEP_GROUP_TAB),
                    new Group(R.string.zp_group_startup, KEY_FORCE_MESSAGES_AS_HOME));
        }
        if (SECTION_INBOX.equals(section)) {
            return Arrays.asList(
                    new Group(R.string.zp_group_inbox_sections, KEY_HIDE_MEDIA_BOX,
                            KEY_HIDE_ZCLOUD_BANNER),
                    new Group(R.string.zp_group_category_controls, KEY_FILTER_POPOVER_CATEGORIES,
                            KEY_DEFAULT_INBOX_FILTER, KEY_CATEGORY_GROUPS,
                            KEY_CATEGORY_STRANGERS, KEY_CATEGORY_OA));
        }
        if (SECTION_TELEMETRY.equals(section)) {
            return Arrays.asList(
                    new Group(R.string.zp_group_all_sinks, Group.SpecialBlock.TELEMETRY),
                    new Group(R.string.zp_group_analytics_database, KEY_DISABLE_EVENT_ANALYTICS,
                            KEY_DISABLE_SCREEN_ANALYTICS, KEY_DISABLE_SESSION_ANALYTICS,
                            KEY_DISABLE_VIEW_ANALYTICS),
                    new Group(R.string.zp_group_identifiers_reports, KEY_DISABLE_CRASHLYTICS,
                            KEY_DISABLE_AD_ID, KEY_DISABLE_MEASUREMENT_BIND));
        }
        if (SECTION_ADS.equals(section)) {
            return Collections.singletonList(new Group(R.string.zp_group_in_app_ads,
                    KEY_HIDE_MESSAGE_ADS, KEY_HIDE_FEED_ADS, KEY_HIDE_PROMO_SERVICES));
        }
        if (SECTION_NOTIFICATIONS.equals(section)) {
            return Collections.singletonList(new Group(R.string.zp_filter_controls,
                    KEY_HIDE_PROMO_NOTIFICATIONS, KEY_RECORD_NOTIFICATION_HISTORY));
        }
        if (SECTION_ME.equals(section)) {
            return Collections.singletonList(new Group(R.string.zp_group_me_services,
                    KEY_HIDE_QR_WALLET, KEY_HIDE_ZCLOUD, KEY_HIDE_ZSTYLE, KEY_HIDE_ZBUSINESS));
        }
        if (SECTION_CHAT.equals(section)) {
            return Arrays.asList(
                    new Group(R.string.zp_group_message_rendering, KEY_HIDE_REACTION_ROW),
                    new Group(R.string.zp_group_status_privacy,
                            KEY_BLOCK_SEEN_STATUS, KEY_BLOCK_TYPING_STATUS));
        }
        if (SECTION_CALLS.equals(section)) {
            return Collections.singletonList(
                    new Group(R.string.zp_group_call_recording, KEY_AUTO_RECORD_CALLS,
                            KEY_CALL_RECORDING_NOTIFICATIONS));
        }
        if (SECTION_BACKUP.equals(section)) {
            return Collections.singletonList(
                    new Group(R.string.zp_group_backup_schedule,
                            KEY_BACKUP_FREQUENT_PUSH, KEY_BACKUP_PUSH_INTERVAL));
        }
        if (SECTION_DEVELOPER.equals(section)) {
            return Arrays.asList(
                    new Group(R.string.zp_group_call_diagnostics, KEY_CALL_RECORDING_PROBE),
                    new Group(R.string.zp_group_developer_tools,
                            Group.SpecialBlock.DEVELOPER_TOOLS));
        }
        return Collections.emptyList();
    }

    public static final class Item {
        public final String section;
        public final String key;
        public final int titleRes;
        public final int summaryRes;
        public final boolean implemented;
        public final boolean defaultEnabled;
        public final Requirement requirement;

        public Item(String section, String key, int titleRes, int summaryRes,
                    boolean implemented, boolean defaultEnabled) {
            this(section, key, titleRes, summaryRes, implemented, defaultEnabled,
                    Requirement.NONE);
        }

        public Item(String section, String key, int titleRes, int summaryRes,
                    boolean implemented, boolean defaultEnabled, Requirement requirement) {
            this.section = section;
            this.key = key;
            this.titleRes = titleRes;
            this.summaryRes = summaryRes;
            this.implemented = implemented;
            this.defaultEnabled = defaultEnabled;
            this.requirement = requirement == null ? Requirement.NONE : requirement;
        }
    }

    public static final List<Item> ITEMS = Collections.unmodifiableList(Arrays.asList(
            new Item(SECTION_NAVIGATION, KEY_HIDE_DISCOVERY_TAB,
                    R.string.zp_tweak_hide_discovery, R.string.zp_tweak_hide_discovery_summary,
                    true, false),
            new Item(SECTION_NAVIGATION, KEY_HIDE_TIMELINE_TAB,
                    R.string.zp_tweak_hide_timeline, R.string.zp_tweak_hide_timeline_summary,
                    true, false),
            new Item(SECTION_NAVIGATION, KEY_KEEP_GROUP_TAB,
                    R.string.zp_tweak_keep_group, R.string.zp_tweak_keep_group_summary,
                    true, false),
            new Item(SECTION_NAVIGATION, KEY_FORCE_MESSAGES_AS_HOME,
                    R.string.zp_tweak_force_messages_home, R.string.zp_tweak_force_messages_home_summary,
                    true, false),

            new Item(SECTION_ME, KEY_HIDE_QR_WALLET,
                    R.string.zp_tweak_hide_qr_wallet, R.string.zp_tweak_hide_qr_wallet_summary,
                    true, false),
            new Item(SECTION_ME, KEY_HIDE_ZCLOUD,
                    R.string.zp_tweak_hide_zcloud, R.string.zp_tweak_hide_zcloud_summary,
                    true, false),
            new Item(SECTION_ME, KEY_HIDE_ZSTYLE,
                    R.string.zp_tweak_hide_zstyle, R.string.zp_tweak_hide_zstyle_summary,
                    true, false),
            new Item(SECTION_ME, KEY_HIDE_ZBUSINESS,
                    R.string.zp_tweak_hide_zbusiness, R.string.zp_tweak_hide_zbusiness_summary,
                    true, false),

            new Item(SECTION_TELEMETRY, KEY_DISABLE_EVENT_ANALYTICS,
                    R.string.zp_tweak_disable_event_analytics, R.string.zp_tweak_disable_event_analytics_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_SCREEN_ANALYTICS,
                    R.string.zp_tweak_disable_screen_analytics, R.string.zp_tweak_disable_screen_analytics_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_SESSION_ANALYTICS,
                    R.string.zp_tweak_disable_session_analytics, R.string.zp_tweak_disable_session_analytics_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_VIEW_ANALYTICS,
                    R.string.zp_tweak_disable_view_analytics, R.string.zp_tweak_disable_view_analytics_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_CRASHLYTICS,
                    R.string.zp_tweak_disable_crashlytics, R.string.zp_tweak_disable_crashlytics_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_AD_ID,
                    R.string.zp_tweak_disable_ad_id, R.string.zp_tweak_disable_ad_id_summary,
                    true, false),
            new Item(SECTION_TELEMETRY, KEY_DISABLE_MEASUREMENT_BIND,
                    R.string.zp_tweak_disable_measurement_bind, R.string.zp_tweak_disable_measurement_bind_summary,
                    true, false),

            new Item(SECTION_INBOX, KEY_HIDE_MEDIA_BOX,
                    R.string.zp_tweak_hide_media_box, R.string.zp_tweak_hide_media_box_summary,
                    true, false),
            new Item(SECTION_INBOX, KEY_HIDE_ZCLOUD_BANNER,
                    R.string.zp_tweak_hide_zcloud_banner, R.string.zp_tweak_hide_zcloud_banner_summary,
                    true, false, Requirement.RESOURCE_HOOKS),
            new Item(SECTION_INBOX, KEY_FILTER_POPOVER_CATEGORIES,
                    R.string.zp_tweak_inbox_chip_bar, R.string.zp_tweak_inbox_chip_bar_summary,
                    true, false),
            new Item(SECTION_INBOX, KEY_CATEGORY_GROUPS,
                    R.string.zp_tweak_groups_chip, R.string.zp_tweak_groups_chip_summary,
                    true, false),
            new Item(SECTION_INBOX, KEY_CATEGORY_STRANGERS,
                    R.string.zp_tweak_strangers_chip, R.string.zp_tweak_strangers_chip_summary,
                    true, false),
            new Item(SECTION_INBOX, KEY_CATEGORY_OA,
                    R.string.zp_tweak_oa_chip, R.string.zp_tweak_oa_chip_summary,
                    true, false),

            new Item(SECTION_CHAT, KEY_HIDE_REACTION_ROW,
                    R.string.zp_tweak_hide_reaction_row, R.string.zp_tweak_hide_reaction_row_summary,
                    true, false),
            new Item(SECTION_CHAT, KEY_BLOCK_SEEN_STATUS,
                    R.string.zp_tweak_block_seen_status, R.string.zp_tweak_block_seen_status_summary,
                    true, false),
            new Item(SECTION_CHAT, KEY_BLOCK_TYPING_STATUS,
                    R.string.zp_tweak_block_typing_status, R.string.zp_tweak_block_typing_status_summary,
                    true, false),

            new Item(SECTION_CALLS, KEY_AUTO_RECORD_CALLS,
                    R.string.zp_tweak_auto_record_calls, R.string.zp_tweak_auto_record_calls_summary,
                    true, false),
            new Item(SECTION_CALLS, KEY_CALL_RECORDING_NOTIFICATIONS,
                    R.string.zp_tweak_recording_notifications, R.string.zp_tweak_recording_notifications_summary,
                    true, false),
            new Item(SECTION_BACKUP, KEY_BACKUP_FREQUENT_PUSH,
                    R.string.zp_tweak_backup_frequent, R.string.zp_tweak_backup_frequent_summary,
                    true, false),
            new Item(SECTION_DEVELOPER, KEY_CALL_RECORDING_PROBE,
                    R.string.zp_tweak_call_recording_probe, R.string.zp_tweak_call_recording_probe_summary,
                    true, false),

            new Item(SECTION_ADS, KEY_HIDE_MESSAGE_ADS,
                    R.string.zp_tweak_hide_message_ads, R.string.zp_tweak_hide_message_ads_summary,
                    true, false),
            new Item(SECTION_ADS, KEY_HIDE_FEED_ADS,
                    R.string.zp_tweak_hide_feed_ads, R.string.zp_tweak_hide_feed_ads_summary,
                    true, false),
            new Item(SECTION_ADS, KEY_HIDE_PROMO_SERVICES,
                    R.string.zp_tweak_hide_promo_services, R.string.zp_tweak_hide_promo_services_summary,
                    true, false),
            new Item(SECTION_NOTIFICATIONS, KEY_HIDE_PROMO_NOTIFICATIONS,
                    R.string.zp_tweak_hide_promo_notifications, R.string.zp_tweak_hide_promo_notifications_summary,
                    true, false),
            new Item(SECTION_NOTIFICATIONS, KEY_RECORD_NOTIFICATION_HISTORY,
                    R.string.zp_tweak_record_notification_history, R.string.zp_tweak_record_notification_history_summary,
                    true, false)
    ));

    private Tweaks() {
    }

    public static boolean defaultEnabled(String key) {
        for (Item item : ITEMS) {
            if (item.key.equals(key)) {
                return item.defaultEnabled;
            }
        }
        return false;
    }

    public static boolean isImplemented(String key) {
        for (Item item : ITEMS) {
            if (item.key.equals(key)) {
                return item.implemented;
            }
        }
        return false;
    }

}
