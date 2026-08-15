package com.ez.zalopatch;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SymbolSchema {
    public static final int SUPPORTED_BUNDLE_VERSION = 1;
    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String TARGET_PACKAGE = "com.zing.zalo";
    public static final String MODULE_PACKAGE = "com.ez.zalopatch";
    public static final String ASSET_NAME = "symbol-schema.json";
    private static volatile Active cachedHookSchema;
    private static volatile long cachedHookVersionCode = Long.MIN_VALUE;
    private static volatile String moduleApkPath;

    private SymbolSchema() {
    }

    public static Active active(Context context) {
        context = hookContext(context);
        long installedVersionCode = installedZaloVersionCode(context);
        Active bundled = bundledForVersion(context, installedVersionCode);
        if (bundled.valid) {
            return bundled;
        }
        Active remote = remoteForModule(context, installedVersionCode);
        return remote == null ? bundled : remote;
    }

    public static Active activeForHooks(Context context) {
        context = hookContext(context);
        long installedVersionCode = installedZaloVersionCode(context);
        Active cached = cachedHookSchema;
        if (cached != null && cachedHookVersionCode == installedVersionCode) {
            return cached;
        }
        synchronized (SymbolSchema.class) {
            cached = cachedHookSchema;
            if (cached == null || cachedHookVersionCode != installedVersionCode) {
                cached = bundledForVersion(context, installedVersionCode);
                if (!cached.valid) {
                    Active remote = remoteFromProvider(context, installedVersionCode);
                    if (remote != null && remote.valid) {
                        cached = remote;
                    }
                }
                cachedHookSchema = cached;
                cachedHookVersionCode = installedVersionCode;
            }
            return cached;
        }
    }

    public static Active bundled(Context context) {
        context = hookContext(context);
        return bundledForVersion(context, installedZaloVersionCode(context));
    }

    public static void invalidate() {
        cachedHookSchema = null;
        cachedHookVersionCode = Long.MIN_VALUE;
    }

    private static Active remoteForModule(Context context, long installedVersionCode) {
        if (context == null || !MODULE_PACKAGE.equals(context.getPackageName())) {
            return null;
        }
        try {
            android.content.SharedPreferences preferences = TweakStore.preferences(context);
            String storedLightweight = preferences.getString(
                    ZaloArtifactState.KEY_LIGHTWEIGHT, "");
            ZaloArtifactIdentity current = ZaloArtifactIdentity.capture(context, false);
            if (!current.lightweightKey.equals(storedLightweight)) {
                return null;
            }
            SymbolCatalogContract.Entry entry = SymbolCatalogCache.load(context,
                    installedVersionCode,
                    preferences.getString(ZaloArtifactState.KEY_BASE_SHA256, ""),
                    preferences.getString(ZaloArtifactState.KEY_SIGNER_SHA256, ""));
            if (entry == null) return null;
            Active active = select(entry.profileJson,
                    "Remote catalog " + entry.sequence, installedVersionCode);
            return active.valid ? active : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Active selectRemoteEntry(SymbolCatalogContract.Entry entry, long installedVersionCode) {
        if (entry == null) return null;
        try {
            Active active = select(entry.profileJson,
                    "Remote catalog " + entry.sequence, installedVersionCode);
            return active.valid ? active : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Active remoteFromProvider(Context context, long installedVersionCode) {
        if (context == null) return null;
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://com.ez.zalopatch.config/symbol_schema"),
                null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int jsonIndex = cursor.getColumnIndex("json");
            int sourceIndex = cursor.getColumnIndex("source");
            int validIndex = cursor.getColumnIndex("valid");
            if (jsonIndex < 0 || validIndex < 0 || cursor.getInt(validIndex) != 1) return null;
            String json = cursor.getString(jsonIndex);
            String source = sourceIndex < 0 ? "Remote catalog" : cursor.getString(sourceIndex);
            if (json == null || source == null || !source.startsWith("Remote catalog")) return null;
            Active active = select(json, source, installedVersionCode);
            return active.valid ? active : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Active bundledForVersion(Context context, long installedVersionCode) {
        try {
            return select(readBundledJson(context), "Bundled", installedVersionCode);
        } catch (Throwable throwable) {
            return Active.invalid("Bundled", "",
                    "Bundled schema unavailable: " + throwable.getMessage(), false,
                    installedVersionCode, 0, "");
        }
    }

    static Active selectBundledForVersion(Context context, long installedVersionCode) {
        return bundledForVersion(hookContext(context), installedVersionCode);
    }

    public static void setModuleApkPath(String path) {
        if (path != null && !path.isEmpty() && !path.equals(moduleApkPath)) {
            moduleApkPath = path;
            cachedHookSchema = null;
            cachedHookVersionCode = Long.MIN_VALUE;
        }
    }

    public static void setModuleApkPathFromClassLoader(ClassLoader classLoader) {
        String path = apkPathFromClassLoader(classLoader);
        if (path == null || path.isEmpty()) {
            path = apkPathFromProcMaps();
        }
        setModuleApkPath(path);
    }

    public static String metadataSummary(Context context) {
        Active active = active(context);
        StringBuilder summary = new StringBuilder();
        if (active.valid) {
            summary.append(active.source).append(" · schema v")
                    .append(active.schemaVersion).append(".").append(active.schemaRevision)
                    .append(" · exact Zalo ").append(active.minCode);
            if (active.source.startsWith("Bundled")) {
                summary.append(" · ").append(active.profileCount).append(" profiles bundled");
            }
            summary
                    .append(" · ").append(active.string("artifact.verification", "unverified"));
        } else {
            summary.append("Bundled profiles · installed Zalo ")
                    .append(active.installedVersionCode)
                    .append(" · supported ")
                    .append(active.supportedVersionCodes.isEmpty()
                            ? "none" : active.supportedVersionCodes)
                    .append("\n").append(active.validation);
        }
        return summary.toString();
    }

    public static List<ProfileInfo> catalog(Context context) {
        try {
            JSONObject bundle = new JSONObject(readBundledJson(hookContext(context)));
            JSONArray profiles = bundle.optJSONArray("profiles");
            if (profiles == null) return Collections.emptyList();
            ArrayList<ProfileInfo> result = new ArrayList<>();
            for (int index = 0; index < profiles.length(); index++) {
                JSONObject profile = profiles.optJSONObject(index);
                if (profile == null) continue;
                JSONObject version = profile.optJSONObject("zalo_version");
                JSONObject artifact = profile.optJSONObject("artifact");
                JSONObject symbols = profile.optJSONObject("symbols");
                ArrayList<String> paths = new ArrayList<>();
                collectLeafPaths(symbols, "symbols", paths);
                Collections.sort(paths);
                result.add(new ProfileInfo(
                        version == null ? -1L : version.optLong("min_code", -1L),
                        profile.optInt("schema_revision", -1),
                        artifact == null ? "unverified"
                                : artifact.optString("verification", "unverified"),
                        version == null ? "" : version.optString("notes", ""), paths));
            }
            Collections.sort(result, (left, right) -> Long.compare(right.versionCode,
                    left.versionCode));
            return Collections.unmodifiableList(result);
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static void collectLeafPaths(JSONObject object, String prefix, List<String> output) {
        if (object == null) return;
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            String path = prefix + "." + key;
            if (value instanceof JSONObject) {
                collectLeafPaths((JSONObject) value, path, output);
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                if (array.length() == 0) {
                    output.add(path + " = []");
                } else {
                    for (int index = 0; index < array.length(); index++) {
                        Object item = array.opt(index);
                        if (item instanceof JSONObject) {
                            collectLeafPaths((JSONObject) item,
                                    path + "[" + index + "]", output);
                        } else {
                            output.add(path + "[" + index + "] = " + String.valueOf(item));
                        }
                    }
                }
            } else {
                output.add(path + " = " + String.valueOf(value));
            }
        }
    }

    public static Health health(Context context) {
        Active active = active(context);
        long installedVersionCode = installedZaloVersionCode(context);
        if (!active.valid) {
            String status = active.bundleValid ? "stale" : "failed";
            return new Health(installedVersionCode, active, status, active.validation);
        }
        if (installedVersionCode <= 0L) {
            return new Health(installedVersionCode, active, "stale",
                    "Installed Zalo version unavailable; no profile selected.");
        }
        ZaloArtifactState.Compatibility artifact = context != null
                && MODULE_PACKAGE.equals(context.getPackageName())
                ? ZaloArtifactState.currentCompatibility(context)
                : ZaloArtifactState.forHooks(context);
        if (!artifact.compatible) {
            return new Health(installedVersionCode, active,
                    "failed".equals(artifact.status) ? "failed" : "stale", artifact.reason);
        }
        return new Health(installedVersionCode, active, "ok",
                "Exact symbol profile selected from " + active.source + " for Zalo "
                        + installedVersionCode
                        + " (schema v" + active.schemaVersion + "." + active.schemaRevision + ").");
    }

    public static String string(Context context, String path, String fallback) {
        Active active = activeForHooks(context);
        return active.valid ? active.string(path, fallback) : "";
    }

    public static ResolvedString stringForHooks(Context context, String path, String fallback) {
        Active active = activeForHooks(context);
        if (!active.valid) {
            return new ResolvedString("", "no_compatible_profile", active.source, false);
        }
        String value = active.string(path, "");
        if (!value.isEmpty()) {
            return new ResolvedString(value, sourceKey(active.source), active.source, false);
        }
        Active bundled = bundled(context);
        value = bundled.string(path, "");
        if (!value.isEmpty()) {
            return new ResolvedString(value, "bundled_direct", bundled.source, false);
        }
        return new ResolvedString(fallback, "java_fallback", "Java fallback", true);
    }

    private static String sourceKey(String source) {
        if (source == null || source.isEmpty()) {
            return "unknown";
        }
        return source.toLowerCase(java.util.Locale.US).replace(' ', '_');
    }

    public static int integer(Context context, String path, int fallback) {
        Active active = activeForHooks(context);
        return active.valid ? active.integer(path, fallback) : fallback;
    }

    public static List<String> strings(Context context, String path, String... fallback) {
        Active active = activeForHooks(context);
        if (!active.valid) {
            return new ArrayList<>();
        }
        List<String> values = active.strings(path);
        if (!values.isEmpty()) {
            return values;
        }
        ArrayList<String> defaults = new ArrayList<>();
        if (fallback != null) {
            for (String item : fallback) {
                defaults.add(item);
            }
        }
        return defaults;
    }

    static Active select(String json, String source, long installedVersionCode) throws JSONException {
        JSONObject bundle = new JSONObject(json);
        JSONArray profiles = bundle.optJSONArray("profiles");
        if (profiles == null) {
            Active legacy = parseProfile(bundle, json, source, installedVersionCode, 1, "");
            if (!legacy.valid || installedVersionCode <= 0L
                    || legacy.minCode != installedVersionCode || legacy.maxCode != installedVersionCode) {
                String supported = legacy.minCode == legacy.maxCode && legacy.minCode > 0
                        ? String.valueOf(legacy.minCode) : "";
                return Active.invalid(source, json,
                        "No exact bundled symbol profile for Zalo " + installedVersionCode
                                + supportedSuffix(supported), legacy.bundleValid,
                        installedVersionCode, legacy.valid ? 1 : 0, supported);
            }
            return legacy;
        }

        int bundleVersion = bundle.optInt("bundle_version", -1);
        if (bundleVersion != SUPPORTED_BUNDLE_VERSION) {
            return Active.invalid(source, json, "Unsupported bundle_version " + bundleVersion,
                    false, installedVersionCode, profiles.length(), "");
        }
        String packageName = bundle.optString("zalo_package", "");
        if (!TARGET_PACKAGE.equals(packageName)) {
            return Active.invalid(source, json, "Wrong target package " + packageName,
                    false, installedVersionCode, profiles.length(), "");
        }
        if (profiles.length() == 0) {
            return Active.invalid(source, json, "Symbol profile bundle is empty",
                    false, installedVersionCode, 0, "");
        }

        ArrayList<Integer> codes = new ArrayList<>();
        JSONObject selected = null;
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null) {
                return Active.invalid(source, json, "Invalid symbol profile at index " + i,
                        false, installedVersionCode, profiles.length(), joinCodes(codes));
            }
            JSONObject range = profile.optJSONObject("zalo_version");
            int profileCode = range == null ? -1 : range.optInt("min_code", -1);
            Active parsed = parseProfile(profile, profile.toString(), source,
                    installedVersionCode, profiles.length(), "");
            if (!parsed.valid) {
                return Active.invalid(source, json,
                        "Invalid symbol profile at index " + i + ": " + parsed.validation,
                        false, installedVersionCode, profiles.length(), joinCodes(codes));
            }
            int code = parsed.minCode;
            if (codes.contains(code)) {
                return Active.invalid(source, json, "Duplicate symbol profile for Zalo " + code,
                        false, installedVersionCode, profiles.length(), joinCodes(codes));
            }
            codes.add(code);
            if (code == installedVersionCode) {
                selected = profile;
            }
        }

        String supported = joinCodes(codes);
        if (selected == null) {
            return Active.invalid(source, "",
                    "No exact bundled symbol profile for Zalo " + installedVersionCode
                            + supportedSuffix(supported), true,
                    installedVersionCode, profiles.length(), supported);
        }
        return parseProfile(selected, selected.toString(),
                source + " profile " + installedVersionCode, installedVersionCode,
                profiles.length(), supported);
    }

    private static Active parseProfile(JSONObject root, String json, String source,
                                       long installedVersionCode, int profileCount,
                                       String supportedVersionCodes) {
        int schemaVersion = root.optInt("schema_version", -1);
        int schemaRevision = root.optInt("schema_revision", schemaVersion);
        String packageName = root.optString("zalo_package", "");
        JSONObject range = root.optJSONObject("zalo_version");
        int minCode = range == null ? 0 : range.optInt("min_code", 0);
        int maxCode = range == null ? 0 : range.optInt("max_code", 0);
        String validation = validate(schemaVersion, packageName, minCode, maxCode);
        if (validation.isEmpty()) {
            validation = validateArtifact(root.optJSONObject("artifact"));
        }
        boolean valid = validation.isEmpty();
        return new Active(root, json, source, schemaVersion, schemaRevision, minCode, maxCode,
                validation, valid, valid, installedVersionCode, profileCount,
                supportedVersionCodes);
    }

    private static String validate(int schemaVersion, String packageName, int minCode, int maxCode) {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return "Unsupported schema_version " + schemaVersion;
        }
        if (!TARGET_PACKAGE.equals(packageName)) {
            return "Wrong target package " + packageName;
        }
        if (minCode <= 0 || maxCode != minCode) {
            return "Symbol profile must target one exact Zalo versionCode";
        }
        return "";
    }

    private static String validateArtifact(JSONObject artifact) {
        if (artifact == null) {
            return "Symbol profile artifact metadata missing";
        }
        String baseHash = artifact.optString("base_apk_sha256", "");
        String signerHash = artifact.optString("signer_sha256", "");
        String hookCodeApk = artifact.optString("hook_code_apk", "");
        String verification = artifact.optString("verification", "");
        if (!isSha256(baseHash)) {
            return "Symbol profile base APK SHA-256 invalid";
        }
        if (!isSha256(signerHash)) {
            return "Symbol profile signer SHA-256 invalid";
        }
        if (!"base".equals(hookCodeApk)) {
            return "Symbol profile hook code APK must be base";
        }
        if (!("static-verified".equals(verification)
                || "instrumented".equals(verification)
                || "device-smoke-tested".equals(verification)
                || "device-verified".equals(verification))) {
            return "Symbol profile verification state invalid";
        }
        return "";
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String joinCodes(List<Integer> codes) {
        StringBuilder result = new StringBuilder();
        for (Integer code : codes) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(code);
        }
        return result.toString();
    }

    private static String supportedSuffix(String supported) {
        return supported == null || supported.isEmpty() ? "" : "; bundled versions: " + supported;
    }

    public static long installedZaloVersionCode(Context context) {
        if (context == null) {
            return -1L;
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String readAsset(Context context, String name) throws IOException {
        try (InputStream input = context.getAssets().open(name)) {
            return readStream(input);
        }
    }

    private static String readBundledJson(Context context) throws IOException {
        try {
            if (context != null && MODULE_PACKAGE.equals(context.getPackageName())) {
                return readAsset(context, ASSET_NAME);
            }
        } catch (IOException ignored) {
        }
        Context moduleContext = moduleContext(context);
        if (moduleContext != null) {
            try {
                return readAsset(moduleContext, ASSET_NAME);
            } catch (IOException ignored) {
            }
            try {
                String sourceDir = moduleContext.getApplicationInfo().sourceDir;
                if (sourceDir != null && !sourceDir.isEmpty()) {
                    setModuleApkPath(sourceDir);
                }
            } catch (Throwable ignored) {
            }
        }
        String embeddedJson = BundledSymbolSchemaJson.json();
        if (embeddedJson != null && !embeddedJson.isEmpty()) {
            return embeddedJson;
        }
        String path = moduleApkPath;
        if (path == null || path.isEmpty()) {
            path = apkPathFromClassLoader(SymbolSchema.class.getClassLoader());
            if (path == null || path.isEmpty()) {
                path = apkPathFromProcMaps();
            }
            setModuleApkPath(path);
        }
        if (path == null || path.isEmpty()) {
            throw new IOException("module APK path unavailable");
        }
        try (ZipFile zipFile = new ZipFile(path)) {
            ZipEntry entry = zipFile.getEntry("assets/" + ASSET_NAME);
            if (entry == null) {
                throw new IOException("asset missing from module APK " + path);
            }
            try (InputStream input = zipFile.getInputStream(entry)) {
                return readStream(input);
            }
        } catch (IOException exception) {
            throw new IOException("module=" + path + ", " + exception.getMessage(), exception);
        }
    }

    private static Context moduleContext(Context context) {
        context = hookContext(context);
        if (context == null) {
            return null;
        }
        if (MODULE_PACKAGE.equals(context.getPackageName())) {
            return context;
        }
        try {
            return context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context hookContext(Context context) {
        if (context != null) {
            return context;
        }
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Object application = activityThreadClass.getMethod("currentApplication").invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            if (activityThread != null) {
                Object systemContext = activityThreadClass.getMethod("getSystemContext").invoke(activityThread);
                if (systemContext instanceof Context) {
                    return (Context) systemContext;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String apkPathFromClassLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return "";
        }
        String text = String.valueOf(classLoader);
        int packageIndex = text.indexOf("com.ez.zalopatch");
        if (packageIndex < 0) {
            return "";
        }
        int apkEnd = text.indexOf(".apk", packageIndex);
        if (apkEnd < 0) {
            return "";
        }
        int start = text.lastIndexOf('"', apkEnd);
        int altStart = text.lastIndexOf(' ', apkEnd);
        start = Math.max(start, altStart);
        if (start < 0) {
            start = text.lastIndexOf('[', apkEnd);
        }
        if (start < 0) {
            start = 0;
        } else {
            start++;
        }
        return text.substring(start, apkEnd + 4);
    }

    private static String apkPathFromProcMaps() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int packageIndex = line.indexOf("com.ez.zalopatch");
                if (packageIndex < 0) {
                    continue;
                }
                int apkEnd = line.indexOf(".apk", packageIndex);
                if (apkEnd < 0) {
                    continue;
                }
                int start = line.lastIndexOf(' ', apkEnd);
                return line.substring(start < 0 ? 0 : start + 1, apkEnd + 4);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String readStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    public static final class Active {
        public final JSONObject root;
        public final String json;
        public final String source;
        public final int schemaVersion;
        public final int schemaRevision;
        public final int minCode;
        public final int maxCode;
        public final String validation;
        public final boolean valid;
        public final boolean bundleValid;
        public final long installedVersionCode;
        public final int profileCount;
        public final String supportedVersionCodes;

        Active(JSONObject root, String json, String source, int schemaVersion, int schemaRevision,
               int minCode, int maxCode, String validation, boolean valid, boolean bundleValid,
               long installedVersionCode, int profileCount, String supportedVersionCodes) {
            this.root = root;
            this.json = json;
            this.source = source;
            this.schemaVersion = schemaVersion;
            this.schemaRevision = schemaRevision;
            this.minCode = minCode;
            this.maxCode = maxCode;
            this.validation = validation;
            this.valid = valid;
            this.bundleValid = bundleValid;
            this.installedVersionCode = installedVersionCode;
            this.profileCount = profileCount;
            this.supportedVersionCodes = supportedVersionCodes == null ? "" : supportedVersionCodes;
        }

        static Active invalid(String source, String json, String validation, boolean bundleValid,
                              long installedVersionCode, int profileCount,
                              String supportedVersionCodes) {
            return new Active(new JSONObject(), json, source, -1, -1, 0, 0,
                    validation == null ? "Invalid schema" : validation, false, bundleValid,
                    installedVersionCode, profileCount, supportedVersionCodes);
        }

        public String string(String path, String fallback) {
            Object value = value(path);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
        }

        public int integer(String path, int fallback) {
            Object value = value(path);
            return value instanceof Number ? ((Number) value).intValue() : fallback;
        }

        public List<String> strings(String path) {
            ArrayList<String> values = new ArrayList<>();
            Object value = value(path);
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    String item = array.optString(i, "");
                    if (!item.isEmpty()) {
                        values.add(item);
                    }
                }
            }
            return values;
        }

        public String settingString(String key, String field, String fallback) {
            JSONObject settings = object("ui.settings");
            JSONObject item = settings == null ? null : settings.optJSONObject(key);
            if (item == null) {
                return fallback;
            }
            String value = item.optString(field, "");
            return value.isEmpty() ? fallback : value;
        }

        public String sectionString(String section, String field, String fallback) {
            JSONObject sections = object("ui.sections");
            JSONObject item = sections == null ? null : sections.optJSONObject(section);
            if (item == null) {
                return fallback;
            }
            String value = item.optString(field, "");
            return value.isEmpty() ? fallback : value;
        }

        Object value(String path) {
            if (TextUtils.isEmpty(path)) {
                return null;
            }
            Object current = root;
            String[] parts = path.split("\\.");
            for (String part : parts) {
                if (!(current instanceof JSONObject)) {
                    return null;
                }
                JSONObject object = (JSONObject) current;
                if (!object.has(part)) {
                    return null;
                }
                current = object.opt(part);
            }
            return current == JSONObject.NULL ? null : current;
        }

        private JSONObject object(String path) {
            Object value = value(path);
            return value instanceof JSONObject ? (JSONObject) value : null;
        }
    }

    public static final class ProfileInfo {
        public final long versionCode;
        public final int schemaRevision;
        public final String verification;
        public final String notes;
        public final List<String> symbolPaths;

        ProfileInfo(long versionCode, int schemaRevision, String verification, String notes,
                    List<String> symbolPaths) {
            this.versionCode = versionCode;
            this.schemaRevision = schemaRevision;
            this.verification = verification;
            this.notes = notes;
            this.symbolPaths = Collections.unmodifiableList(new ArrayList<>(symbolPaths));
        }
    }

    public static final class ResolvedString {
        public final String value;
        public final String source;
        public final String schemaSource;
        public final boolean fallback;

        ResolvedString(String value, String source, String schemaSource, boolean fallback) {
            this.value = value;
            this.source = source;
            this.schemaSource = schemaSource;
            this.fallback = fallback;
        }
    }

    public static final class Health {
        public final long installedVersionCode;
        public final Active schema;
        public final String status;
        public final String message;

        Health(long installedVersionCode, Active schema, String status, String message) {
            this.installedVersionCode = installedVersionCode;
            this.schema = schema;
            this.status = status;
            this.message = message;
        }
    }
}
