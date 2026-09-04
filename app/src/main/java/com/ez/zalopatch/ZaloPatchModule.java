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

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LibXposed API 102 module entry.
 *
 * <p>Package work starts from {@link #onPackageReady} (not {@code onPackageLoaded},
 * which requires API 29 while this module supports API 26+). Only {@code com.zing.zalo}
 * is handled; every other package loaded into the process is ignored without
 * {@code detach()}, because detaching on a non-matching package would also drop the
 * Zalo package callback when it arrives later in the same process.
 */
public final class ZaloPatchModule extends XposedModule {
    static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String TAG = "ZaloPatch";
    private static final long RESOURCE_HOOK_OBSERVATION_WINDOW_MS = 5_000L;
    private final AtomicBoolean featuresStarted = new AtomicBoolean(false);
    private volatile String processName = "";
    private volatile boolean systemServer;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        systemServer = param.isSystemServer();
        XpHooks.attach(this);
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (XpHooks.framework() == null) {
            XpHooks.attach(this);
        }
        HookConfig.init();
        try {
            SymbolSchema.setModuleApkPath(getModuleApplicationInfo().sourceDir);
        } catch (Throwable throwable) {
            XpLog.w(TAG + ": module APK path unavailable: " + throwable);
        }
        SymbolSchema.setModuleApkPathFromClassLoader(ZaloPatchModule.class.getClassLoader());
        // An empty process name means onModuleLoaded has not run; treat it like the
        // legacy null process name, which counted as the main process.
        boolean mainProcess = processName.isEmpty() || TARGET_PACKAGE.equals(processName);
        XpLog.i(TAG + ": loaded into "
                + (processName.isEmpty() ? param.getPackageName() : processName));
        hookContextCapture(param.getClassLoader(), mainProcess);
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

    /**
     * Old code path for hot reload. Accepts reload only when no call recording or
     * finalization is active in this process; executors are stopped before returning.
     */
    @Override
    public boolean onHotReloading(XposedModuleInterface.HotReloadingParam param) {
        if (CallRecordingFeature.blocksHotReload()) {
            XpLog.w(TAG + ": hot reload rejected while a recording is active");
            return false;
        }
        CallRecordingFeature.prepareHotReload();
        BottomTabsFeature.retireForHotReload();
        XpLog.i(TAG + ": hot reload accepted");
        return true;
    }

    /**
     * New code path for hot reload. Package lifecycle callbacks are not replayed, so
     * old hooks are removed and the feature set is reinstalled against the live Zalo
     * application, whose context and class loader outlive the module generation.
     */
    @Override
    public void onHotReloaded(XposedModuleInterface.HotReloadedParam param) {
        try {
            for (io.github.libxposed.api.XposedInterface.HookHandle old
                    : param.getOldHookHandles()) {
                try {
                    old.unhook();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            XpLog.w(TAG + ": old hook cleanup failed: " + throwable);
        }
        XpHooks.attach(this);
        HookConfig.init();
        HookConfig.reload();
        try {
            SymbolSchema.setModuleApkPath(getModuleApplicationInfo().sourceDir);
        } catch (Throwable throwable) {
            XpLog.w(TAG + ": module APK path unavailable: " + throwable);
        }
        boolean mainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        Context application = HookConfig.resolveFallbackContextForHooks();
        ClassLoader classLoader;
        if (application != null) {
            HookConfig.setAppContext(application);
            ClassLoader appClassLoader = application.getClassLoader();
            classLoader = appClassLoader != null
                    ? appClassLoader
                    : ZaloPatchModule.class.getClassLoader();
        } else {
            classLoader = ZaloPatchModule.class.getClassLoader();
        }
        XpLog.i(TAG + ": hot reloaded in " + param.getProcessName());
        startFeatures(classLoader, mainProcess);
    }
}
