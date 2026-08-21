package com.ez.zalopatch;

import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;

/** Hook-process detector and bounded transport for runtime capability evidence. */
public final class RuntimeEnvironmentReporter {
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String[] LSPATCH_CLASSES = {
            "org.lsposed.lspatch.loader.LSPApplication",
            "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub",
            "org.lsposed.lspatch.service.ILSPApplicationService"
    };
    private static final String[] LSPOSED_CLASSES = {
            "org.lsposed.lspd.impl.LSPosedBridge",
            "org.lsposed.lspd.nativebridge.NativeAPI",
            "org.lsposed.lspd.nativebridge.HookBridge",
            "org.lsposed.lspd.loader.Main"
    };

    private RuntimeEnvironmentReporter() {
    }

    public static void report(Context context, boolean resourceHooksObserved) {
        report(context, resourceHooksObserved
                ? RuntimeEnvironment.ResourceHooks.OBSERVED
                : RuntimeEnvironment.ResourceHooks.PENDING);
    }

    public static void report(Context context, RuntimeEnvironment.ResourceHooks resourceHooks) {
        if (context == null || !TARGET_PACKAGE.equals(context.getPackageName())) return;
        RuntimeEnvironment.Framework framework = RuntimeEnvironment.detect(
                hasLspatchMarker(context), hasAnyClass(LSPOSED_CLASSES, context),
                xposedApiVersion(), runtimeClassLoaderEvidence(context));
        long zaloVersion = SymbolSchema.installedZaloVersionCode(context);
        Bundle extras = new Bundle();
        extras.putString("framework", framework.value());
        RuntimeEnvironment.ResourceHooks safeResourceHooks = resourceHooks == null
                ? RuntimeEnvironment.ResourceHooks.PENDING : resourceHooks;
        extras.putString("resource_hooks_status", safeResourceHooks.value());
        extras.putBoolean("resource_hooks_observed",
                safeResourceHooks == RuntimeEnvironment.ResourceHooks.OBSERVED);
        extras.putInt("module_version_code", BuildConfig.VERSION_CODE);
        extras.putLong("zalo_version_code", zaloVersion);
        Context providerContext = HookConfig.resolveModuleContextForHooks();
        if (providerContext == null) providerContext = context;
        try {
            Bundle response = providerContext.getContentResolver().call(
                    android.net.Uri.parse("content://com.ez.zalopatch.config"),
                    "record_runtime_environment", null, extras);
            if (response != null && response.getBoolean("recorded", false)) return;
        } catch (Throwable ignored) {
        }
        sendFallback(context, extras);
    }

    private static boolean hasLspatchMarker(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    TARGET_PACKAGE, PackageManager.GET_PROVIDERS);
            if (info.providers != null) {
                for (ProviderInfo provider : info.providers) {
                    if (provider.authority != null
                            && provider.authority.contains("lspatch.documents")) {
                        return true;
                    }
                }
            }
            if (info.applicationInfo != null) {
                String application = info.applicationInfo.className;
                if (startsWithLspatch(application)) return true;
                if (Build.VERSION.SDK_INT >= 28
                        && startsWithLspatch(info.applicationInfo.appComponentFactory)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return hasAnyClass(LSPATCH_CLASSES, context);
    }

    private static boolean startsWithLspatch(String value) {
        return value != null && value.startsWith("org.lsposed.lspatch.");
    }

    private static int xposedApiVersion() {
        try {
            return Math.max(0, XposedBridge.getXposedVersion());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean hasAnyClass(String[] names, Context context) {
        for (String name : names) {
            for (ClassLoader loader : runtimeClassLoaders(context)) {
                try {
                    Class.forName(name, false, loader);
                    return true;
                } catch (ClassNotFoundException | LinkageError ignored) {
                }
            }
        }
        return false;
    }

    private static String[] runtimeClassLoaderEvidence(Context context) {
        ClassLoader[] loaders = runtimeClassLoaders(context);
        String[] evidence = new String[loaders.length * 2];
        int index = 0;
        for (ClassLoader loader : loaders) {
            if (loader == null) continue;
            try {
                evidence[index++] = bounded(loader.getClass().getName(), 256);
            } catch (Throwable ignored) {
            }
            try {
                evidence[index++] = bounded(String.valueOf(loader), 512);
            } catch (Throwable ignored) {
            }
        }
        return evidence;
    }

    private static ClassLoader[] runtimeClassLoaders(Context context) {
        return new ClassLoader[]{
                XposedBridge.class.getClassLoader(),
                RuntimeEnvironmentReporter.class.getClassLoader(),
                context == null ? null : context.getClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                ClassLoader.getSystemClassLoader(),
                null
        };
    }

    private static String bounded(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void sendFallback(Context context, Bundle extras) {
        if (Build.VERSION.SDK_INT < 34) return;
        try {
            Intent intent = new Intent(SelfCheckReceiver.ACTION_RECORD_RUNTIME_ENVIRONMENT);
            intent.setComponent(new ComponentName("com.ez.zalopatch",
                    "com.ez.zalopatch.SelfCheckReceiver"));
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            intent.putExtras(extras);
            BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setShareIdentityEnabled(true);
            context.sendBroadcast(intent, null, options.toBundle());
        } catch (Throwable throwable) {
            Log.i("ZaloPatch", "Runtime environment fallback failed: "
                    + throwable.getClass().getSimpleName());
        }
    }
}
