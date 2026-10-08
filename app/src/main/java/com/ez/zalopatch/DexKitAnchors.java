package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixed DexKit anchor vocabulary for the extended pilot.
 *
 * <p>Each anchor names one obfuscated symbol by its schema path (the same path the
 * hook reads through {@code SymbolSchema}), plus the symbol kind for validation.
 * The order is fixed so serialized descriptor maps are deterministic. Anchors are
 * appended, never renamed or removed: the cache format revision already binds old
 * entries out.
 *
 * <p>Stable (non-obfuscated) companion classes live beside each family fingerprint
 * definition, not here; this catalog carries only the resolvable paths.
 */
public final class DexKitAnchors {
    /** Symbol kinds a resolved descriptor may take. */
    public enum Kind {
        CLASS,
        METHOD,
        FIELD
    }

    /** One resolvable anchor: schema path plus expected symbol kind. */
    public static final class Anchor {
        public final String path;
        public final Kind kind;

        Anchor(String path, Kind kind) {
            this.path = path;
            this.kind = kind;
        }
    }

    private static final List<Anchor> ANCHORS = new ArrayList<>();

    static {
        // WebView externalize family.
        ANCHORS.add(new Anchor("symbols.webview.redirect_transform_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.webview.companion_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.webview.open_dispatch_method", Kind.METHOD));
        // Passcode grace family.
        ANCHORS.add(new Anchor("symbols.passcode.prefs_int_reader_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.passcode.prefs_int_reader_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.passcode.active_time_setter_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.passcode.active_time_setter_method", Kind.METHOD));
        // Scheduled backup family.
        ANCHORS.add(new Anchor("symbols.backup.interval_reader_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.backup.interval_reader_method", Kind.METHOD));
        // Telemetry analytics-DAO family.
        ANCHORS.add(new Anchor("symbols.telemetry.analytics_event_accessor", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.telemetry.analytics_screen_accessor", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.telemetry.analytics_session_accessor", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.telemetry.analytics_view_accessor", Kind.METHOD));
        // Call recording: the registered CallCallback implementation and the active-peer manager.
        ANCHORS.add(new Anchor("symbols.call_recording.callback_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.call_recording.peer_manager_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.call_recording.peer_manager_instance_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.call_recording.peer_container_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.call_recording.peer_handle_field", Kind.FIELD));
        // Chat big-file expiry state.
        ANCHORS.add(new Anchor("symbols.media.state_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.media.state_classifier_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.media.state_classifier_method", Kind.METHOD));
        // Inbox deleted-group store.
        ANCHORS.add(new Anchor("symbols.inbox.deleted_group_repository_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.inbox.deleted_group_repository_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.inbox.deleted_group_check_method", Kind.METHOD));
        // Bottom-tabs state family (flat leaves; the overlay synthesizes
        // current_tab_symbols/current_methods from exactly this set).
        ANCHORS.add(new Anchor("symbols.bottom_tabs.tabs_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.state_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.enum_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.singleton_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.icon_resolver_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.rebuild_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.refresh_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.hide_discovery_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.group_flag_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.message_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.phonebook_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.group_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.discovery_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.timeline_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.more_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.me_index_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.size_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.message_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.phonebook_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.group_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.discovery_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.timeline_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.more_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.me_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.size_index_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.group_enabled_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.timeline_enabled_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.discovery_enabled_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.more_enabled_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.me_enabled_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.icons_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.bottom_tabs.preloaded_field", Kind.FIELD));
        // Me TabMe-builder family (item layer derived at runtime, not resolved).
        ANCHORS.add(new Anchor("symbols.me.current_builder_method", Kind.METHOD));
        // Inbox category field (normal items and adapter runtime-derived).
        ANCHORS.add(new Anchor("symbols.inbox.category_int_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.inbox.conversation_uid_field", Kind.FIELD));
        // Chat seen/typing repository family: the message repository that owns the
        // distinctive seen-ack and typing send shapes.
        ANCHORS.add(new Anchor("symbols.chat.message_repository_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.chat.send_ack_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.chat.send_typing_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.chat.send_seen_manager_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.chat.seen_ack_class", Kind.CLASS));
        ANCHORS.add(new Anchor("symbols.chat.seen_ack_type_field", Kind.FIELD));
        ANCHORS.add(new Anchor("symbols.chat.send_seen_single_method", Kind.METHOD));
        ANCHORS.add(new Anchor("symbols.chat.send_seen_batch_method", Kind.METHOD));
    }

    private DexKitAnchors() {
    }

    /** Fixed anchor order; the returned list is unmodifiable. */
    public static List<Anchor> all() {
        return Collections.unmodifiableList(ANCHORS);
    }

    /** Anchor paths in fixed order. */
    public static List<String> paths() {
        ArrayList<String> paths = new ArrayList<>(ANCHORS.size());
        for (Anchor anchor : ANCHORS) {
            paths.add(anchor.path);
        }
        return paths;
    }

    /** Looks up one anchor by schema path, or null when unknown. */
    public static Anchor find(String path) {
        if (path == null) {
            return null;
        }
        for (Anchor anchor : ANCHORS) {
            if (anchor.path.equals(path)) {
                return anchor;
            }
        }
        return null;
    }

    /** True when the descriptor matches the anchor kind's name shape. */
    public static boolean validDescriptor(Anchor anchor, String symbol) {
        if (anchor == null || symbol == null || symbol.isEmpty() || symbol.length() > 256) {
            return false;
        }
        if (symbol.contains(";") || symbol.contains("=")) {
            return false;
        }
        switch (anchor.kind) {
            case CLASS:
                return symbol.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
            case METHOD:
            case FIELD:
                return symbol.matches("[A-Za-z_$][A-Za-z0-9_$]*");
            default:
                return false;
        }
    }

    /**
     * Serializes a descriptor map in fixed anchor order: {@code path=symbol;...}.
     * Unknown paths are dropped; invalid descriptors are dropped.
     */
    public static String serialize(Map<String, String> descriptors) {
        StringBuilder output = new StringBuilder();
        if (descriptors == null) {
            return "";
        }
        for (Anchor anchor : ANCHORS) {
            String symbol = descriptors.get(anchor.path);
            if (!validDescriptor(anchor, symbol)) {
                continue;
            }
            if (output.length() > 0) {
                output.append(';');
            }
            output.append(anchor.path).append('=').append(symbol);
        }
        return output.toString();
    }

    /**
     * Parses {@link #serialize} output. Unknown paths and invalid descriptors are
     * dropped; the result preserves fixed anchor order.
     */
    public static Map<String, String> parse(String value) {
        LinkedHashMap<String, String> descriptors = new LinkedHashMap<>();
        if (value == null || value.isEmpty()) {
            return descriptors;
        }
        for (String part : value.split(";", -1)) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String path = part.substring(0, separator);
            String symbol = part.substring(separator + 1);
            Anchor anchor = find(path);
            if (anchor == null || descriptors.containsKey(path)) {
                continue;
            }
            if (validDescriptor(anchor, symbol)) {
                descriptors.put(path, symbol);
            }
        }
        return descriptors;
    }
}
