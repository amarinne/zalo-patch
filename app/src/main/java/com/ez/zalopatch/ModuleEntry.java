package com.ez.zalopatch;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.xposed.core.MainFeatures;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class ModuleEntry implements IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit {
    private static final String TARGET_PACKAGE = "com.zing.zalo";
    private static final String TAG = "ZaloPatch";
    private final AtomicBoolean featuresStarted = new AtomicBoolean(false);

    @Override
    public void initZygote(StartupParam startupParam) {
        SymbolSchema.setModuleApkPath(startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        HookConfig.init();
        SymbolSchema.setModuleApkPathFromClassLoader(ModuleEntry.class.getClassLoader());
        boolean mainProcess = lpparam.processName == null
                || TARGET_PACKAGE.equals(lpparam.processName);
        log("Loaded into " + lpparam.processName);
        hookContextCapture(lpparam.classLoader, mainProcess);
    }

    @Override
    public void handleInitPackageResources(XC_InitPackageResources.InitPackageResourcesParam resparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(resparam.packageName)) {
            return;
        }
        resparam.res.hookLayout(TARGET_PACKAGE, "layout", "messageslist", new de.robv.android.xposed.callbacks.XC_LayoutInflated() {
            @Override
            public void handleLayoutInflated(LayoutInflatedParam liparam) {
                if (!HookConfig.isEnabled(Tweaks.KEY_HIDE_ZCLOUD_BANNER)) {
                    return;
                }
                View fixedBannerContainer = liparam.view.findViewById(
                        liparam.res.getIdentifier("fixed_banner_container", "id", TARGET_PACKAGE)
                );
                if (fixedBannerContainer != null) {
                    hideView(fixedBannerContainer);
                }
            }
        });
    }

    private void hookContextCapture(ClassLoader classLoader, boolean mainProcess) {
        XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof Context)) {
                    return;
                }
                Context context = (Context) param.args[0];
                HookConfig.setAppContext(context);
                ClassLoader appClassLoader = context.getClassLoader();
                startFeatures(appClassLoader != null ? appClassLoader : classLoader, mainProcess);
            }
        });
    }

    private void startFeatures(ClassLoader classLoader, boolean mainProcess) {
        if (!featuresStarted.compareAndSet(false, true)) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                HookConfig.logStartupSnapshot();
                MainFeatures.start(classLoader, mainProcess);
            }
        });
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void hideView(View view) {
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            view.setLayoutParams(layoutParams);
        }
    }
}
