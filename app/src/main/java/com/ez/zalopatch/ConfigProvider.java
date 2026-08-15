package com.ez.zalopatch;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Binder;
import android.os.Process;

import java.util.Map;

public final class ConfigProvider extends ContentProvider {
    public static final String AUTHORITY = "com.ez.zalopatch.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/prefs");
    public static final Uri NOTIFICATION_HISTORY_URI = Uri.parse("content://" + AUTHORITY + "/notification_history");
    private static final String SELF_CHECK_PREFIX = "selfcheck.";
    private static final String KEY_SELF_CHECK_GENERATION = "internal.selfcheck_generation";
    private static final String KEY_SELF_CHECK_RUN_ID = "internal.selfcheck_run_id";
    private static final UriMatcher MATCHER = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int ALL_PREFS = 1;
    private static final int ONE_PREF = 2;
    private static final int SELF_CHECK = 3;
    private static final int ONE_SELF_CHECK = 4;
    private static final int SYMBOL_SCHEMA = 5;
    private static final int NOTIFICATION_HISTORY = 6;

    static {
        MATCHER.addURI(AUTHORITY, "prefs", ALL_PREFS);
        MATCHER.addURI(AUTHORITY, "prefs/*", ONE_PREF);
        MATCHER.addURI(AUTHORITY, "self_check", SELF_CHECK);
        MATCHER.addURI(AUTHORITY, "self_check/*", ONE_SELF_CHECK);
        MATCHER.addURI(AUTHORITY, "symbol_schema", SYMBOL_SCHEMA);
        MATCHER.addURI(AUTHORITY, "notification_history", NOTIFICATION_HISTORY);
    }

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            CallRecordingStore.recoverAsync(getContext());
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!syncCallerAllowed()) return null;
        if (getContext() == null) {
            return null;
        }
        TweakStore.initialize(getContext());
        SharedPreferences prefs = TweakStore.preferences(getContext());
        int match = MATCHER.match(uri);
        if (match == SELF_CHECK || match == ONE_SELF_CHECK) {
            return querySelfCheck(uri, prefs, match == ONE_SELF_CHECK);
        }
        if (match == SYMBOL_SCHEMA) {
            return querySymbolSchema(getContext());
        }
        if (match == NOTIFICATION_HISTORY) {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "type", "value"});
        String targetKey = null;
        if (match == ONE_PREF && uri.getPathSegments().size() >= 2) {
            targetKey = uri.getLastPathSegment();
        }

        if (targetKey != null) {
            Settings.Setting<?> setting = Settings.find(targetKey);
            if (setting != null) {
                addSettingRow(cursor, getContext(), setting);
                return cursor;
            }
            Object value = prefs.getAll().get(targetKey);
            if (value != null) {
                addRawRow(cursor, targetKey, value);
            }
            return cursor;
        }

        for (Settings.Setting<?> setting : Settings.all()) {
            addSettingRow(cursor, getContext(), setting);
        }
        Map<String, ?> all = prefs.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (Settings.find(entry.getKey()) == null) {
                addRawRow(cursor, entry.getKey(), entry.getValue());
            }
        }
        return cursor;
    }

    private static Cursor querySymbolSchema(android.content.Context context) {
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "source", "schema_version", "schema_revision", "min_code", "max_code", "valid",
                "validation", "json", "installed_code", "profile_count", "supported_codes",
                "artifact_status", "artifact_generation", "artifact_verification",
                "artifact_compatible", "catalog_status", "catalog_sequence", "catalog_digest"
        });
        SymbolSchema.Active active = SymbolSchema.active(context);
        cursor.addRow(new Object[]{
                active.source,
                active.schemaVersion,
                active.schemaRevision,
                active.minCode,
                active.maxCode,
                active.valid ? 1 : 0,
                active.validation,
                active.json,
                active.installedVersionCode,
                active.profileCount,
                active.supportedVersionCodes,
                TweakStore.preferences(context).getString(ZaloArtifactState.KEY_STATUS, "pending"),
                TweakStore.preferences(context).getString(ZaloArtifactState.KEY_GENERATION, ""),
                active.string("artifact.verification", "unverified"),
                ZaloArtifactState.currentCompatibility(context).compatible ? 1 : 0,
                TweakStore.preferences(context).getString(
                        ZaloArtifactState.KEY_CATALOG_STATUS, "missing"),
                TweakStore.preferences(context).getInt(
                        ZaloArtifactState.KEY_CATALOG_SEQUENCE, 0),
                TweakStore.preferences(context).getString(
                        ZaloArtifactState.KEY_CATALOG_DIGEST, "")
        });
        return cursor;
    }

    private static Cursor querySelfCheck(Uri uri, SharedPreferences prefs, boolean one) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"feature", "status", "install_count", "hit_count", "target", "detail", "error", "updated_at", "artifact_generation", "run_id"});
        if (one && uri.getPathSegments().size() >= 2) {
            addSelfCheckRow(cursor, prefs, uri.getLastPathSegment());
            return cursor;
        }
        java.util.TreeSet<String> features = new java.util.TreeSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (!key.startsWith(SELF_CHECK_PREFIX)) {
                continue;
            }
            int end = key.lastIndexOf('.');
            if (end > SELF_CHECK_PREFIX.length()) {
                features.add(key.substring(SELF_CHECK_PREFIX.length(), end));
            }
        }
        for (String feature : features) {
            addSelfCheckRow(cursor, prefs, feature);
        }
        return cursor;
    }

    private static void addSelfCheckRow(MatrixCursor cursor, SharedPreferences prefs, String feature) {
        String prefix = SELF_CHECK_PREFIX + feature + ".";
        if (!prefs.contains(prefix + "status")) {
            return;
        }
        cursor.addRow(new Object[]{
                feature,
                prefs.getString(prefix + "status", ""),
                prefs.getInt(prefix + "install_count", 0),
                prefs.getInt(prefix + "hit_count", 0),
                prefs.getString(prefix + "target", ""),
                prefs.getString(prefix + "detail", ""),
                prefs.getString(prefix + "error", ""),
                prefs.getLong(prefix + "updated_at", 0L),
                prefs.getString(prefix + "artifact_generation", ""),
                prefs.getString(prefix + "run_id", "")
        });
    }

    private static void addSettingRow(MatrixCursor cursor, android.content.Context context, Settings.Setting<?> setting) {
        if (setting.type == Settings.Type.BOOLEAN) {
            boolean value = SettingsStore.getBoolean(context, setting.key);
            cursor.addRow(new Object[]{setting.key, Boolean.class.getSimpleName(), String.valueOf(value)});
        } else if (setting.type == Settings.Type.INT) {
            int value = SettingsStore.getInt(context, setting.key);
            cursor.addRow(new Object[]{setting.key, Integer.class.getSimpleName(), String.valueOf(value)});
        }
    }

    private static void addRawRow(MatrixCursor cursor, String key, Object value) {
        String type = value == null ? "null" : value.getClass().getSimpleName();
        cursor.addRow(new Object[]{key, type, value == null ? null : String.valueOf(value)});
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd." + AUTHORITY + ".prefs";
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null) {
            return null;
        }
        if ("record_self_check".equals(method)) {
            if (!callerAllowed() || extras == null) return null;
            ContentValues values = extras.getParcelable("values");
            String feature = extras.getString("feature", "");
            int recorded = values == null || feature.isEmpty()
                    ? 0 : recordSelfCheck(getContext(), feature, values);
            Bundle result = new Bundle();
            result.putInt("recorded", recorded);
            return result;
        }
        if ("record_notification_history".equals(method)) {
            if (!callerAllowed() || extras == null
                    || !SettingsStore.getBoolean(getContext(), Tweaks.KEY_RECORD_NOTIFICATION_HISTORY)) {
                return null;
            }
            ContentValues values = extras.getParcelable("values");
            if (values == null) return null;
            long id = new NotificationHistoryStore(getContext()).record(values);
            Bundle result = new Bundle();
            result.putLong("id", id);
            return result;
        }
        if ("reconcile_zalo_artifact".equals(method)) {
            if (!syncCallerAllowed()) return null;
            Bundle result = new Bundle();
            result.putBoolean("scheduled", ZaloArtifactState.schedule(getContext()));
            return result;
        }
        if ("record_runtime_discovery_evidence".equals(method)) {
            if (!callerAllowed() || extras == null) return null;
            long versionCode = extras.getLong("version_code", -1L);
            boolean recorded = RemapEvidenceStore.record(getContext(), versionCode,
                    extras.getString("kind", ""), extras.getString("value", ""));
            Bundle result = new Bundle();
            result.putBoolean("recorded", recorded);
            return result;
        }
        if ("complete_runtime_discovery".equals(method)) {
            if (!callerAllowed()) return null;
            long versionCode;
            try {
                versionCode = Long.parseLong(arg == null ? "" : arg);
            } catch (NumberFormatException ignored) {
                return null;
            }
            DiagnosticsState.completeRuntimeDiscovery(getContext(), versionCode);
            Bundle result = new Bundle();
            result.putBoolean("completed", true);
            return result;
        }
        if (!"sync_properties".equals(method)) {
            return null;
        }
        if (!syncCallerAllowed()) return null;
        TweakStore.initialize(getContext());
        TweakStore.syncProperties(getContext());
        Bundle result = new Bundle();
        NotificationRuleStore.RuleSet rules = NotificationRuleStore.load(getContext());
        boolean synced = false;
        try {
            String expected = NotificationRuleStore.encode(rules);
            synced = expected.equals(SettingsPropertyMirror.readBlob(NotificationRuleStore.MIRROR_KEY));
        } catch (Exception ignored) {
        }
        result.putBoolean("synced", synced);
        result.putInt("notification_rule_count", rules.total());
        return result;
    }

    private boolean syncCallerAllowed() {
        return callerAllowed() || uidOwnsPackage("com.android.shell");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (getContext() == null || values == null) {
            return null;
        }
        if (!callerAllowed()) {
            return null;
        }
        int match = MATCHER.match(uri);
        TweakStore.initialize(getContext());
        if (match == NOTIFICATION_HISTORY) {
            if (!SettingsStore.getBoolean(getContext(), Tweaks.KEY_RECORD_NOTIFICATION_HISTORY)) {
                return null;
            }
            long id = new NotificationHistoryStore(getContext()).record(values);
            return id >= 0L ? Uri.withAppendedPath(uri, String.valueOf(id)) : null;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (getContext() == null || MATCHER.match(uri) != ONE_SELF_CHECK
                || values == null) {
            return 0;
        }
        if (!callerAllowed()) return -1;
        String feature = uri.getLastPathSegment();
        if (feature == null || feature.isEmpty()) return 0;
        return recordSelfCheck(getContext(), feature, values);
    }

    static int recordSelfCheck(android.content.Context context, String feature,
                               ContentValues values) {
        TweakStore.initialize(context);
        SharedPreferences preferences = TweakStore.preferences(context);
        String currentLightweight = ZaloArtifactState.currentLightweight(context);
        String currentEpoch = ZaloArtifactState.currentEvidenceEpoch(context);
        String currentGeneration = preferences.getString(ZaloArtifactState.KEY_GENERATION, "");
        String currentProfileHash = preferences.getString(ZaloArtifactState.KEY_PROFILE_SHA256, "");
        Integer moduleVersionCode = values.getAsInteger("module_version_code");
        if (!currentLightweight.equals(values.getAsString("artifact_lightweight"))
                || !currentGeneration.equals(values.getAsString("artifact_generation"))
                || moduleVersionCode == null || moduleVersionCode != BuildConfig.VERSION_CODE
                || !currentProfileHash.equals(values.getAsString("profile_sha256"))) {
            return -2;
        }
        String storedEpoch = preferences.getString(KEY_SELF_CHECK_GENERATION, "");
        String storedRunId = preferences.getString(KEY_SELF_CHECK_RUN_ID, "");
        String runId = values.getAsString("run_id");
        if (runId == null || runId.isEmpty()) return -3;
        if (!currentEpoch.equals(storedEpoch)) {
            clearSelfCheck(preferences);
            storedRunId = "";
        }
        if (storedRunId.isEmpty()) {
            storedRunId = runId;
        } else if (!runId.equals(storedRunId)) {
            if ("symbol_schema".equals(feature)) {
                clearSelfCheck(preferences);
                storedRunId = runId;
            } else {
                return 0;
            }
        }
        SharedPreferences.Editor editor = preferences.edit();
        String prefix = SELF_CHECK_PREFIX + feature + ".";
        put(editor, prefix, values, "status");
        put(editor, prefix, values, "target");
        put(editor, prefix, values, "detail");
        put(editor, prefix, values, "error");
        put(editor, prefix, values, "artifact_generation");
        put(editor, prefix, values, "run_id");
        putInt(editor, prefix, values, "install_count");
        putInt(editor, prefix, values, "hit_count");
        putLong(editor, prefix, values, "updated_at");
        editor.putString(KEY_SELF_CHECK_GENERATION, currentEpoch);
        if (!storedRunId.isEmpty()) editor.putString(KEY_SELF_CHECK_RUN_ID, storedRunId);
        editor.apply();
        RuntimeStatusTraceStore.record(context, feature, values);
        return 1;
    }

    private static void put(SharedPreferences.Editor editor, String prefix, ContentValues values, String key) {
        if (values.containsKey(key)) {
            editor.putString(prefix + key, values.getAsString(key));
        }
    }

    private static void putInt(SharedPreferences.Editor editor, String prefix, ContentValues values, String key) {
        Integer value = values.getAsInteger(key);
        if (value != null) {
            editor.putInt(prefix + key, value);
        }
    }

    private static void putLong(SharedPreferences.Editor editor, String prefix, ContentValues values, String key) {
        Long value = values.getAsLong(key);
        if (value != null) {
            editor.putLong(prefix + key, value);
        }
    }

    private boolean callerAllowed() {
        return getContext() != null && (Binder.getCallingUid() == Process.myUid()
                || uidOwnsPackage("com.zing.zalo"));
    }

    private boolean uidOwnsPackage(String packageName) {
        if (getContext() == null) return false;
        String[] packages = getContext().getPackageManager()
                .getPackagesForUid(Binder.getCallingUid());
        if (packages == null) return false;
        for (String candidate : packages) {
            if (packageName.equals(candidate)) return true;
        }
        return false;
    }

    private static void clearSelfCheck(SharedPreferences preferences) {
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(SELF_CHECK_PREFIX)) editor.remove(key);
        }
        editor.remove(KEY_SELF_CHECK_GENERATION);
        editor.remove(KEY_SELF_CHECK_RUN_ID);
        editor.apply();
    }
}
