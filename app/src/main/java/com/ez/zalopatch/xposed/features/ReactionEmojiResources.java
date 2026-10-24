package com.ez.zalopatch.xposed.features;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

import com.ez.zalopatch.R;

import java.io.File;

/** Trusted module APK identity used only when host package visibility hides this module. */
public final class ReactionEmojiResources {
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static volatile ApplicationInfo moduleApplicationInfo;

    private ReactionEmojiResources() {
    }

    /** The modern framework supplies the current module identity, including split APK data. */
    public static void setModuleApplicationInfo(ApplicationInfo info) {
        if (info == null || !MODULE_PACKAGE.equals(info.packageName)
                || info.sourceDir == null || !new File(info.sourceDir).isAbsolute()) {
            moduleApplicationInfo = null;
            return;
        }
        moduleApplicationInfo = new ApplicationInfo(info);
    }

    /** Legacy Xposed supplies the current module APK path in StartupParam. */
    public static void setModuleApkPath(String path) {
        if (path == null || path.isEmpty()) {
            moduleApplicationInfo = null;
            return;
        }
        ApplicationInfo info = new ApplicationInfo();
        info.packageName = MODULE_PACKAGE;
        info.sourceDir = path;
        info.publicSourceDir = path;
        setModuleApplicationInfo(info);
    }

    /**
     * Loads the module resource table without asking the host to discover this package.
     * Resources are rebuilt for each host configuration so density and night mode stay live.
     */
    @SuppressWarnings("deprecation")
    static Resources load(Context host) throws PackageManager.NameNotFoundException {
        ApplicationInfo identity = moduleApplicationInfo;
        if (host == null || identity == null) {
            return null;
        }
        Resources module = host.getPackageManager().getResourcesForApplication(
                new ApplicationInfo(identity));
        if (!valid(module)) {
            return null;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(host.getResources().getDisplayMetrics());
        Resources configured = new Resources(module.getAssets(), metrics,
                new Configuration(host.getResources().getConfiguration()));
        return valid(configured) ? configured : null;
    }

    static boolean valid(Resources resources) {
        if (resources == null) {
            return false;
        }
        try {
            return (MODULE_PACKAGE + ":style/Theme.ZaloPatch").equals(
                    resources.getResourceName(R.style.Theme_ZaloPatch));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
