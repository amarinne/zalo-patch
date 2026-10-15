package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.DexKitAdtimaFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;

/** Disables only Adtima's offline-window and active-view/impression Boolean gates. */
public final class AdtimaOfflineTrackingFeature extends Feature {
    private static final String FEATURE_OFFLINE_WINDOW = "adtima.offline_window";
    private static final String FEATURE_TRACKING_GATE = "adtima.tracking_gate";
    private static final AtomicBoolean OFFLINE_OVERRIDE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean TRACKING_OVERRIDE_REPORTED = new AtomicBoolean();

    private final boolean anchorsReady;
    private final String unavailableReason;
    private final AtomicBoolean armed = new AtomicBoolean();

    public AdtimaOfflineTrackingFeature(ClassLoader classLoader, boolean anchorsReady,
                                        String unavailableReason) {
        super(classLoader);
        this.anchorsReady = anchorsReady;
        this.unavailableReason = unavailableReason == null || unavailableReason.isEmpty()
                ? "exact-profile or current-host DexKit bindings unavailable" : unavailableReason;
    }

    @Override
    public String getFeatureName() {
        return "Adtima offline/tracking gates";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_DISABLE_ADTIMA_OFFLINE_TRACKING)) {
            SelfCheckRegistry.markDisabled(FEATURE_OFFLINE_WINDOW,
                    DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS);
            SelfCheckRegistry.markDisabled(FEATURE_TRACKING_GATE,
                    DexKitAdtimaFingerprint.TRACKING_GATE_CLASS);
            return;
        }
        if (!anchorsReady) {
            SelfCheckRegistry.markStale(FEATURE_OFFLINE_WINDOW,
                    DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS, unavailableReason);
            SelfCheckRegistry.markStale(FEATURE_TRACKING_GATE,
                    DexKitAdtimaFingerprint.TRACKING_GATE_CLASS, unavailableReason);
            return;
        }

        String windowClass = schemaString(DexKitAdtimaFingerprint.OFFLINE_WINDOW_CLASS);
        String windowName = schemaString(DexKitAdtimaFingerprint.OFFLINE_WINDOW_METHOD);
        String trackingClass = schemaString(DexKitAdtimaFingerprint.TRACKING_GATE_CLASS);
        String trackingName = schemaString(DexKitAdtimaFingerprint.TRACKING_GATE_METHOD);
        Method window = null;
        Method tracking = null;
        try {
            window = resolveBooleanGate(windowClass, windowName);
            tracking = resolveBooleanGate(trackingClass, trackingName);
            if (window.equals(tracking)) {
                throw new NoSuchMethodException("Adtima gates are not distinct");
            }
        } catch (Throwable failure) {
            markFailed(failure, windowClass, windowName, trackingClass, trackingName);
            return;
        }

        XpHooks.Handle windowHandle = null;
        XpHooks.Handle trackingHandle = null;
        try {
            windowHandle = hookBooleanGate(FEATURE_OFFLINE_WINDOW, window,
                    OFFLINE_OVERRIDE_REPORTED);
            trackingHandle = hookBooleanGate(
                    FEATURE_TRACKING_GATE, tracking, TRACKING_OVERRIDE_REPORTED);
            armed.set(true);
            SelfCheckRegistry.markInstalled(FEATURE_OFFLINE_WINDOW, display(window), 1);
            SelfCheckRegistry.markInstalled(FEATURE_TRACKING_GATE, display(tracking), 1);
            log("Adtima Boolean gates installed");
        } catch (Throwable failure) {
            armed.set(false);
            if (windowHandle != null) windowHandle.unhook();
            if (trackingHandle != null) trackingHandle.unhook();
            SelfCheckRegistry.markFailed(FEATURE_OFFLINE_WINDOW, display(window), failure);
            SelfCheckRegistry.markFailed(FEATURE_TRACKING_GATE, display(tracking), failure);
            log("Failed to install Adtima gate hooks: " + failure.getClass().getSimpleName());
        }
    }

    private XpHooks.Handle hookBooleanGate(final String feature, final Method method,
                                          final AtomicBoolean overrideReported) {
        AtomicBoolean failed = new AtomicBoolean();
        return XpHooks.hookMethod(feature, method, null, new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) throws Throwable {
                if (!armed.get() || failed.get() || param.hasThrowable()
                        || !Boolean.TRUE.equals(param.getResult())) return;
                try {
                    param.setResult(Boolean.FALSE);
                    if (overrideReported.compareAndSet(false, true)) {
                        SelfCheckRegistry.markSuppressed(
                                feature, display(method), "true -> false");
                    }
                } catch (Throwable callbackFailure) {
                    if (failed.compareAndSet(false, true)) {
                        SelfCheckRegistry.markFailed(feature, display(method), callbackFailure);
                    }
                    throw callbackFailure;
                }
            }
        });
    }

    private Method resolveBooleanGate(String className, String methodName) throws Throwable {
        if (className.isEmpty() || methodName.isEmpty()) {
            throw new NoSuchMethodException("Adtima gate mapping is incomplete");
        }
        Class<?> owner = XpReflect.findClass(className, classLoader);
        Method method = owner.getDeclaredMethod(methodName);
        if (!Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != Boolean.TYPE
                || method.getParameterTypes().length != 0) {
            throw new NoSuchMethodException(className + "#" + methodName
                    + " is not static boolean()");
        }
        method.setAccessible(true);
        return method;
    }

    private void markFailed(Throwable failure, String windowClass, String windowName,
                            String trackingClass, String trackingName) {
        SelfCheckRegistry.markFailed(FEATURE_OFFLINE_WINDOW,
                display(windowClass, windowName), failure);
        SelfCheckRegistry.markFailed(FEATURE_TRACKING_GATE,
                display(trackingClass, trackingName), failure);
    }

    private String schemaString(String path) {
        return SymbolSchema.string(HookConfig.resolveModuleContextForHooks(), path, "");
    }

    private static String display(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "()Z";
    }

    private static String display(String className, String methodName) {
        return (className == null ? "" : className) + "#"
                + (methodName == null ? "" : methodName) + "()Z";
    }
}
