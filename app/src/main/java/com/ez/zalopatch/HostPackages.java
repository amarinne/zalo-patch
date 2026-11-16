package com.ez.zalopatch;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Process;

import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Keeps the installed package identity separate from Zalo's unchanged Java namespace. */
public final class HostPackages {
    public static final String ZALO = "com.zing.zalo";
    private static final String MODULE = "com.ez.zalopatch";
    private static final String PREFERENCES = "trusted_zalo_packages";

    private HostPackages() {}

    static Set<String> parse(String value) {
        TreeSet<String> packages = new TreeSet<>();
        for (String item : (value == null ? "" : value).split("[\\s,;]+")) {
            if (item.isEmpty() || ZALO.equals(item)) continue;
            if (item.length() > 255 || !item.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
                    || MODULE.equals(item)) {
                throw new IllegalArgumentException("Invalid Zalo package: " + item);
            }
            packages.add(item);
            if (packages.size() > 16) throw new IllegalArgumentException("Too many Zalo packages");
        }
        return packages;
    }

    static Set<String> additional(Context context) {
        return new TreeSet<>(base(context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getStringSet("packages", Collections.emptySet()));
    }

    static boolean save(Context context, String value) {
        return base(context).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putStringSet("packages", parse(value)).commit();
    }

    static boolean allows(Set<String> additional, String packageName) {
        return ZALO.equals(packageName) || (packageName != null && additional.contains(packageName));
    }

    /** The package comes from Android's UID lookup, never from an intent extra. */
    static String callerPackage(Context context, int uid) {
        if (context == null || uid < 0) return null;
        if (uid == Process.myUid()) return target(context);
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        return selectCaller(additional(context), packages);
    }

    static String selectCaller(Set<String> additional, String[] packages) {
        if (packages == null) return null;
        String found = null;
        for (String candidate : packages) {
            if (!allows(additional, candidate)) continue;
            // Shared UIDs cannot identify which of two admitted installations sent the request.
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    static boolean mainProcess(String packageName, String applicationProcess, String processName) {
        return processName == null || processName.isEmpty()
                || processName.equals(applicationProcess == null || applicationProcess.isEmpty()
                        ? packageName : applicationProcess);
    }

    public static boolean mainProcess(Context context, String processName) {
        return mainProcess(context.getPackageName(), context.getApplicationInfo().processName, processName);
    }

    public static boolean canLoad(String packageName, ClassLoader loader) {
        if (ZALO.equals(packageName)) return true;
        if (packageName == null || MODULE.equals(packageName)) return false;
        try {
            Class.forName("com.zing.zalo.ui.ZaloLauncherActivity", false, loader);
            Class.forName("com.vng.zing.vn.zrtc.PeerJNI", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    public static boolean admitted(Context context) {
        if (ZALO.equals(context.getPackageName())) return true;
        try {
            Bundle result = context.getContentResolver().call(ConfigProvider.URI,
                    "is_host_allowed", context.getPackageName(), null);
            return result != null && result.getBoolean("allowed", false);
        } catch (Throwable unavailable) {
            return false;
        }
    }

    public static String target(Context context) {
        if (context instanceof ScopedContext) return ((ScopedContext) context).hostPackage;
        if (context != null && !MODULE.equals(context.getPackageName())
                && !"android".equals(context.getPackageName())) return context.getPackageName();
        // Hook evidence sometimes uses a module package context while still running as Zalo.
        if (context != null && Process.myUid() != context.getApplicationInfo().uid) {
            Context host = HookConfig.resolveFallbackContextForHooks();
            if (host != null && !MODULE.equals(host.getPackageName())
                    && !"android".equals(host.getPackageName())) return host.getPackageName();
        }
        return ZALO;
    }

    public static boolean renamed(Context context) { return !ZALO.equals(target(context)); }

    /**
     * Existing host scope. The original keeps the empty suffix so its preference, file, property
     * and remote names never move; a registered package keeps the deployed {@code sha256(package)}
     * suffix used by {@link ScopedContext}.
     */
    static String scopeSuffix(String packageName) {
        return packageName == null || ZALO.equals(packageName)
                ? "" : ZaloArtifactIdentity.sha256(packageName);
    }

    static String scopeSuffix(Context context) {
        return context instanceof ScopedContext ? ((ScopedContext) context).suffix : "";
    }

    /**
     * Host package for module-side transport. A scoped context (or a raw non-module host context)
     * selects its own package; every base module/UI context keeps the original's wire names.
     */
    static String transportHost(Context context) {
        if (context instanceof ScopedContext) return ((ScopedContext) context).hostPackage;
        try {
            String packageName = context == null ? null : context.getPackageName();
            if (packageName != null && !MODULE.equals(packageName)
                    && !"android".equals(packageName)) return packageName;
        } catch (Throwable unavailable) {
            // Module/UI contexts in host tests may not expose a package; keep the original name.
        }
        return ZALO;
    }

    /**
     * Short deterministic transport namespace derived from the existing package scope; it is
     * not a persisted target identifier. Nine hex characters keep the longest blob chunk name
     * inside bionic's 31-character limit: debug.zp. + scope (9) + . + key (8) + .127 (4).
     */
    static String transportScope(String packageName) {
        String suffix = scopeSuffix(packageName);
        return suffix.isEmpty() ? "" : suffix.substring(0, 9);
    }

    /** Explicit target selection stays inside the existing module/shell control boundary. */
    static boolean explicitHostAllowed(int callingUid, int moduleUid, boolean shellPackage) {
        return callingUid == moduleUid || shellPackage;
    }

    static Context scoped(Context module, String packageName) {
        if (packageName == null || ZALO.equals(packageName)) return base(module);
        return new ScopedContext(base(module), packageName);
    }

    static Context base(Context context) {
        return context instanceof ScopedContext ? ((ScopedContext) context).base : context;
    }

    private static final class ScopedContext extends ContextWrapper {
        final Context base;
        final String hostPackage;
        final String suffix;

        ScopedContext(Context context, String hostPackage) {
            super(context);
            base = context;
            this.hostPackage = hostPackage;
            suffix = ZaloArtifactIdentity.sha256(hostPackage);
        }

        @Override public Context getApplicationContext() { return this; }

        @Override public android.content.pm.ApplicationInfo getApplicationInfo() {
            return base.getApplicationInfo();
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return base.getSharedPreferences(name + ".host." + suffix, Context.MODE_PRIVATE);
        }

        @Override public File getFilesDir() {
            File directory = new File(base.getFilesDir(), "hosts/" + suffix);
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Host storage unavailable");
            }
            return directory;
        }

        /** SQLiteOpenHelper resolves through this method, so history/state databases stay scoped. */
        @Override public File getDatabasePath(String name) {
            File directory = new File(getFilesDir(), "databases");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("Host database storage unavailable");
            }
            return new File(directory, name);
        }

        /**
         * Older SQLiteOpenHelper versions open through Context instead of getDatabasePath();
         * keep both entry points on the scoped database directory.
         */
        @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,
                int mode, android.database.sqlite.SQLiteDatabase.CursorFactory factory) {
            return openOrCreateDatabase(name, mode, factory, null);
        }

        @Override public android.database.sqlite.SQLiteDatabase openOrCreateDatabase(String name,
                int mode, android.database.sqlite.SQLiteDatabase.CursorFactory factory,
                android.database.DatabaseErrorHandler errorHandler) {
            int flags = android.database.sqlite.SQLiteDatabase.CREATE_IF_NECESSARY;
            if ((mode & Context.MODE_ENABLE_WRITE_AHEAD_LOGGING) != 0) {
                flags |= android.database.sqlite.SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
            }
            if ((mode & Context.MODE_NO_LOCALIZED_COLLATORS) != 0) {
                flags |= android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS;
            }
            return android.database.sqlite.SQLiteDatabase.openDatabase(
                    getDatabasePath(name).getPath(), factory, flags, errorHandler);
        }
    }
}
