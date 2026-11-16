package com.ez.zalopatch.xposed.features;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IntentFilter;
import android.os.Build;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.ViewGroup;

import com.ez.zalopatch.DexKitMiniChatFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores the host route without replacing its native launch or incoming-message policy. */
public final class MiniChatFeature extends Feature {
    private static final String FEATURE = Tweaks.KEY_NATIVE_MINI_CHAT;
    private static final String WINDOW = FEATURE + ".window";
    private final boolean compatible;
    private final ThreadLocal<Boolean> creatingService = new ThreadLocal<>();
    private final ThreadLocal<Boolean> openingOverlay = new ThreadLocal<>();
    private final ThreadLocal<Context> overlayContext = new ThreadLocal<>();
    private final AtomicBoolean windowReported = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();

    public MiniChatFeature(ClassLoader loader, boolean compatible) {
        super(loader);
        this.compatible = compatible;
    }

    @Override public String getFeatureName() { return "Native Mini Chat"; }

    @Override public void doHook() {
        if (!HookConfig.isEnabled(FEATURE)) {
            SelfCheckRegistry.markDisabled(FEATURE, "native chat-head route");
            SelfCheckRegistry.markDisabled(WINDOW, "native overlay window");
            return;
        }
        if (!compatible) {
            SelfCheckRegistry.markStale(FEATURE, "native chat-head route",
                    "requires exact bindings or current-host discovery; restart after discovery");
            return;
        }
        List<XpHooks.Handle> hooks = new ArrayList<>();
        DexKitMiniChatFingerprint.Binding binding = null;
        boolean[] previous = null;
        try {
            SymbolSchema.Active schema = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
            Map<String, String> symbols = new LinkedHashMap<>();
            for (String path : DexKitMiniChatFingerprint.paths()) symbols.put(path, schema.string(path, ""));
            binding = DexKitMiniChatFingerprint.bind(symbols, classLoader);
            previous = new boolean[]{binding.legacy.getBoolean(null), binding.configured.getBoolean(null)};
            final DexKitMiniChatFingerprint.Binding bound = binding;
            hooks.add(XpHooks.hookMethod(FEATURE, bound.reload, null, param -> {
                if (!param.hasThrowable()) enforce(bound);
            }));
            hooks.add(XpHooks.hookMethod(FEATURE, bound.eligible, param -> enforce(bound), null));
            hooks.add(XpHooks.hookMethod(FEATURE, bound.task.getDeclaredMethod("run"), param -> {
                enforce(bound);
                openingOverlay.set(true);
            }, param -> {
                openingOverlay.remove();
                overlayContext.remove();
            }));
            installReceiverCompatibility(hooks);
            Class<?> host = Class.forName(DexKitMiniChatFingerprint.HOST, false, classLoader);
            installOverlayContext(hooks, host, bound.eligible.getDeclaringClass());
            installImeInsets(hooks, host);
            Method attached = View.class.getDeclaredMethod("onAttachedToWindow");
            hooks.add(XpHooks.hookMethod(WINDOW, attached, null, param -> {
                if (!param.hasThrowable() && host.isInstance(param.thisObject)
                        && ((View) param.thisObject).isAttachedToWindow()
                        && windowReported.compareAndSet(false, true)) {
                    SelfCheckRegistry.markSuppressed(WINDOW, host.getName(), "native overlay attached");
                }
            }));
            SelfCheckRegistry.markInstalled(WINDOW, host.getName(), 1);
            enforce(bound);
            SelfCheckRegistry.markInstalled(FEATURE, "native chat-head route", hooks.size());
            SelfCheckRegistry.markSuppressed(FEATURE, "native chat-head route",
                    "legacy capability enabled; Android bubble route disabled");
        } catch (Throwable error) {
            for (XpHooks.Handle hook : hooks) hook.unhook();
            if (binding != null && previous != null) {
                try {
                    binding.legacy.setBoolean(null, previous[0]);
                    binding.configured.setBoolean(null, previous[1]);
                } catch (Throwable restoreError) { error.addSuppressed(restoreError); }
            }
            SelfCheckRegistry.markFailed(FEATURE, "native chat-head route", error);
        }
    }

    /** Native overlay windows otherwise fit system bars but leave the composer behind the IME. */
    private void installImeInsets(List<XpHooks.Handle> hooks, Class<?> host) throws Exception {
        if (Build.VERSION.SDK_INT < 30) return;
        Class<?> manager = Class.forName("android.view.WindowManagerImpl");
        for (String name : new String[]{"addView", "updateViewLayout"}) {
            Method method = manager.getDeclaredMethod(name, View.class, ViewGroup.LayoutParams.class);
            hooks.add(XpHooks.hookMethod(WINDOW, method, param -> {
                if (host.isInstance(param.args[0]) && param.args[1] instanceof WindowManager.LayoutParams) {
                    WindowManager.LayoutParams params = (WindowManager.LayoutParams) param.args[1];
                    params.setFitInsetsTypes(params.getFitInsetsTypes() | WindowInsets.Type.ime());
                }
            }, null));
        }
    }

    /** Give only the native overlay a display-bound UI context on modern Android. */
    private void installOverlayContext(List<XpHooks.Handle> hooks, Class<?> host, Class<?> controller)
            throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        for (Constructor<?> ctor : DexKitMiniChatFingerprint.overlayConstructors(host, controller)) {
            hooks.add(XpHooks.hookConstructor(FEATURE, ctor, param -> {
                if (!Boolean.TRUE.equals(openingOverlay.get())) return;
                Context original = (Context) param.args[0];
                if (original.isUiContext()) return;
                Context context = overlayContext.get();
                if (context == null) {
                    Display display = original.getSystemService(DisplayManager.class)
                            .getDisplay(Display.DEFAULT_DISPLAY);
                    if (display == null) throw new IllegalStateException("Mini Chat display unavailable");
                    context = original.createDisplayContext(display).createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                    context.getTheme().setTo(original.getTheme());
                    overlayContext.set(context);
                }
                param.args[0] = context;
            }));
        }
    }

    private void enforce(DexKitMiniChatFingerprint.Binding binding) throws IllegalAccessException {
        try {
            binding.legacy.setBoolean(null, true);
            binding.configured.setBoolean(null, false);
        } catch (IllegalAccessException error) {
            if (failed.compareAndSet(false, true)) SelfCheckRegistry.markFailed(FEATURE, "capability flags", error);
            throw error;
        }
    }

    /** Android 13+ requires an explicit receiver export flag for this mixed native filter. */
    private void installReceiverCompatibility(List<XpHooks.Handle> hooks) throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        Class<?> service = Class.forName(DexKitMiniChatFingerprint.SERVICE, false, classLoader);
        hooks.add(XpHooks.hookMethod(FEATURE, service.getDeclaredMethod("onCreate"),
                param -> creatingService.set(true), param -> creatingService.remove()));
        Method old = ContextWrapper.class.getDeclaredMethod("registerReceiver",
                BroadcastReceiver.class, IntentFilter.class);
        Method modern = ContextWrapper.class.getDeclaredMethod("registerReceiver",
                BroadcastReceiver.class, IntentFilter.class, int.class);
        hooks.add(XpHooks.hookMethod(FEATURE, old, param -> {
            if (!Boolean.TRUE.equals(creatingService.get()) || !service.isInstance(param.thisObject)
                    || !(param.args[1] instanceof IntentFilter)
                    || !((IntentFilter) param.args[1]).hasAction("com.zing.zalo.ACTION_MAXIMIZE_CHAT_HEAD")) return;
            try {
                param.setResult(modern.invoke(param.thisObject, param.args[0], param.args[1],
                        Context.RECEIVER_NOT_EXPORTED));
            } catch (InvocationTargetException error) { param.setThrowable(error.getCause()); }
        }, null));
    }
}
