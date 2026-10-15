package com.ez.zalopatch;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.ez.zalopatch.xposed.core.MainFeatures;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpLog;
import com.ez.zalopatch.xposed.features.BottomTabsFeature;
import com.ez.zalopatch.xposed.features.CallRecordingFeature;
import com.ez.zalopatch.xposed.features.ZcloudBannerFeature;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loader entry for upstream LSPatch 0.6 build 398 (legacy API 82). */
public final class ModuleEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String TAG = "ZaloPatch";
    private static final long RESOURCE_HOOK_OBSERVATION_WINDOW_MS = 5_000L;
    private final AtomicBoolean featuresStarted = new AtomicBoolean(false);
    @Override public void initZygote(StartupParam param) {
        SymbolSchema.setModuleApkPath(param.modulePath);
    }
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!TARGET_PACKAGE.equals(param.packageName)) return;
        SymbolSchema.setModuleApkPathFromClassLoader(ModuleEntry.class.getClassLoader());
        boolean mainProcess = param.processName == null || TARGET_PACKAGE.equals(param.processName);
        hookContextCapture(param.classLoader, mainProcess);
    }

    private void hookContextCapture(ClassLoader classLoader, boolean mainProcess) {
        try {
            XpHooks.After onAttached = (XpHooks.HookParam param) -> {
                        if (param.args == null || param.args.length == 0
                                || !(param.args[0] instanceof Context)) {
                            return;
                        }
                        Context context = (Context) param.args[0];
                        HookConfig.setAppContext(context);
                        ClassLoader appClassLoader = context.getClassLoader();
                        startFeatures(
                                appClassLoader != null ? appClassLoader : classLoader,
                                mainProcess);
                    };
            XpHooks.hookAllMethods("entry", Application.class, "attach", onAttached);
        } catch (Throwable throwable) {
            XpLog.e(TAG + ": Application#attach hook failed", throwable);
        }
    }

    private void startFeatures(ClassLoader classLoader, boolean mainProcess) {
        if (!featuresStarted.compareAndSet(false, true)) {
            return;
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                HookConfig.logStartupSnapshot();
                MainFeatures.start(classLoader, mainProcess,
                        ZcloudBannerFeature.observed());
                if (mainProcess) {
                    mainHandler.postDelayed(() -> RuntimeEnvironmentReporter.report(
                                    HookConfig.resolveFallbackContextForHooks(),
                                    ZcloudBannerFeature.observed()
                                            ? RuntimeEnvironment.ResourceHooks.OBSERVED
                                            : RuntimeEnvironment.ResourceHooks.UNAVAILABLE),
                            RESOURCE_HOOK_OBSERVATION_WINDOW_MS);
                }
            }
        });
    }

}
