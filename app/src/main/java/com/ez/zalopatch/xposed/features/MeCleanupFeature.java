package com.ez.zalopatch.xposed.features;

import org.json.JSONObject;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.ZinstantRenderedText;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

public final class MeCleanupFeature extends Feature {
    private static final String FEATURE_ITEMS = "me_cleanup.items";
    private static final String FEATURE_VISIBLE_ROWS = "me_cleanup.visible_rows";
    private static final String FEATURE_QR_WALLET = "me_cleanup.qr_wallet";
    private static final String FEATURE_ZCLOUD = "me_cleanup.zcloud";
    private static final String FEATURE_ZSTYLE = "me_cleanup.zstyle";
    private static final String FEATURE_ZBUSINESS = "me_cleanup.zbusiness";
    private static final String FEATURE_REFRESH = "me_cleanup.refresh";
    /** Debug-only host acceptance probe; never registered in a normal process. */
    private static final String FEATURE_E2E = "me_cleanup.e2e";
    static final String E2E_ACTION = "com.ez.zalopatch.behave.ME_E2E";
    private static final String TAB_ME_CLASS = "com.zing.zalo.ui.maintab.me.TabMeView";
    private static final String CURRENT_TAB_ME_ZINSTANT_VIEW_CLASS = "com.zing.zalo.ui.maintab.me.TabMeZinstantView";
    private final AtomicBoolean loggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean debugLoggedOnce = new AtomicBoolean(false);
    private final AtomicBoolean reportedQrWallet = new AtomicBoolean(false);
    private final AtomicBoolean reportedZCloud = new AtomicBoolean(false);
    private final AtomicBoolean reportedZStyle = new AtomicBoolean(false);
    private final AtomicBoolean reportedZBusiness = new AtomicBoolean(false);
    private final java.util.Map<View, int[]> collapsedTemplates =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final java.util.Map<View, Object> templateBinds =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Object e2eLock = new Object();
    private final java.util.ArrayList<E2eTemplateEvent> e2eEvents = new java.util.ArrayList<>();
    private final java.util.Map<View, E2eNativeBind> e2eNativeBinds =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final Pattern e2eRunIdPattern = Pattern.compile("[a-zA-Z0-9-]{1,60}");
    private final android.os.Handler e2eHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private volatile boolean e2eDelayNextCallback;
    private Runnable e2eDelayedCallback;
    private E2eTemplateEvent e2eDelayedEvent;
    private java.lang.ref.WeakReference<View> e2eExclusiveView;
    private E2eNativeLifecycle e2eLifecycle;
    private boolean e2eLifecycleComplete;
    private boolean e2eLifecyclePassed;
    private boolean e2eLateReleaseRejected;
    private String e2eLifecycleError = "";
    private String e2eLifecycleBlocker = "";
    private String e2eActiveRunId = "";
    private long e2eArmGeneration;
    private long e2eRunGeneration;
    private long e2eRunDeadline;
    private JSONObject e2eLifecycleEvidence = new JSONObject();
    private boolean hideQrWallet;
    private boolean hideZCloud;
    private boolean hideZStyle;
    private boolean hideZBusiness;

    public MeCleanupFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "MeCleanup";
    }

    @Override
    public void doHook() throws Throwable {
        hideQrWallet = HookConfig.isEnabled(Tweaks.KEY_HIDE_QR_WALLET);
        hideZCloud = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZCLOUD);
        hideZStyle = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZSTYLE);
        hideZBusiness = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZBUSINESS);

        markItemStatus(FEATURE_QR_WALLET, hideQrWallet, "QR Wallet");
        markItemStatus(FEATURE_ZCLOUD, hideZCloud, "zCloud");
        markItemStatus(FEATURE_ZSTYLE, hideZStyle, "zStyle");
        markItemStatus(FEATURE_ZBUSINESS, hideZBusiness, "zBusiness");

        boolean debugStarted = HookConfig.isDebugEnabled();
        if (!debugStarted) SelfCheckRegistry.markDisabled(FEATURE_E2E, "debug-only host acceptance");
        if (debugStarted) {
            // Register before the all-toggles-off return so native OFF geometry is observable.
            runGuarded("Me E2E probe", FEATURE_E2E, E2E_ACTION, this::hookE2eProbe);
        }
        if (hideZStyle || debugStarted) {
            String owner = hideZStyle ? FEATURE_ZSTYLE : FEATURE_E2E;
            if (!zStyleViewClass().isEmpty()) {
                runGuarded("zStyle TabMe view", owner, zStyleViewClass(), this::hookZStyleView);
            } else if (!zStyleViewExclusive()) {
                runGuarded("Me template marker", owner, "LayoutZinstantTabMe rendered marker",
                        this::hookGenericMeTemplate);
            }
        }
        if (!hideQrWallet && !hideZCloud && !hideZStyle && !hideZBusiness) {
            SelfCheckRegistry.markDisabled(FEATURE_ITEMS, "TabMeView item filters");
            SelfCheckRegistry.markDisabled(FEATURE_REFRESH, "TabMe refresh hooks");
            SelfCheckRegistry.markDisabled(FEATURE_VISIBLE_ROWS, "TextView#setText");
            return;
        }
        runGuarded("Current TabMe builder", FEATURE_ITEMS, tabMeClass() + "#" + currentBuilderMethod(), this::hookCurrentTabMeBuilder);
        runGuarded("Visible TabMe rows", FEATURE_VISIBLE_ROWS,
                "TextView#setText", this::hookVisibleRowText);
        if (debugStarted) {
            runGuarded("Me behave replay", FEATURE_ITEMS, BEHAVE_ACTION, this::hookBehaveReplay);
        }
    }

    /** Test-only replay action for headless verification (`zalo-verify behave me`). */
    static final String BEHAVE_ACTION = "com.ez.zalopatch.behave.ME_REPLAY";
    /** Last builder product, so the probe can replay classification without navigation. */
    private volatile java.util.List<?> lastBuiltItems;

    /**
     * Re-runs item classification on the cached builder product — the same pure path
     * the live hook drives — and reports kept/removed on the existing items row.
     * Session-only: no view or pref writes. Debug-started processes only, re-gated
     * per broadcast.
     */
    private void hookBehaveReplay() throws Throwable {
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) {
            throw new IllegalStateException("application context unavailable");
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ignored, android.content.Intent intent) {
                if (!HookConfig.isDebugCurrentlyEnabled() || intent == null
                        || !BEHAVE_ACTION.equals(intent.getAction())) {
                    return;
                }
                java.util.List<?> items = lastBuiltItems;
                if (items == null) {
                    SelfCheckRegistry.markSuppressed(FEATURE_ITEMS, "behave:me", "items=none");
                    return;
                }
                Object replayed = filterIfNeeded(new java.util.ArrayList<>(items));
                int kept = replayed instanceof java.util.List
                        ? ((java.util.List<?>) replayed).size() : 0;
                SelfCheckRegistry.markSuppressed(FEATURE_ITEMS, "behave:me",
                        "kept=" + kept + " removed=" + (items.size() - kept));
            }
        };
        // Exported: shell-sent test broadcasts cross UIDs (verified on device for the
        // inbox probe). Harmless by construction: debug-started processes only, debug
        // re-checked per broadcast, no extras read, classification replay only.
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                new android.content.IntentFilter(BEHAVE_ACTION),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    /** Debug-only, bounded native observation. No host payload is persisted or fabricated. */
    private void hookE2eProbe() throws Throwable {
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) throw new IllegalStateException("application context unavailable");
        // The runner restores this property. Read it once before Me observer hooks attach,
        // so eager native binds are recorded only for an explicit bounded debug run.
        String startupRunId = debugProperty("debug.zalopatch.e2e_run");
        if (debugPropertyEnabled() && e2eRunIdPattern.matcher(startupRunId).matches()) {
            e2eActiveRunId = startupRunId;
            touchE2eRun();
        }
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context ignored, android.content.Intent intent) {
                if (!debugPropertyEnabled() || intent == null || !E2E_ACTION.equals(intent.getAction())) return;
                String runId = intent.getStringExtra("run_id");
                String phase = intent.getStringExtra("phase");
                if (runId == null || !e2eRunIdPattern.matcher(runId).matches() || phase == null) return;
                if (!"snapshot".equals(phase) && !"arm_late_callback".equals(phase)
                        && !"release_late_callback".equals(phase) && !"template_a_b_a".equals(phase)
                        && !"reset".equals(phase) && !"cancel".equals(phase)) return;
                if (!e2eActiveRunId.isEmpty() && !e2eActiveRunId.equals(runId)) return;
                try {
                    String detail;
                    if ("reset".equals(phase)) {
                        resetE2eState();
                        e2eActiveRunId = runId;
                        touchE2eRun();
                        detail = "{\"reset\":true}";
                    } else {
                        if (!e2eObserving() || !runId.equals(e2eActiveRunId)) return;
                        touchE2eRun();
                        if ("cancel".equals(phase)) {
                            resetE2eState();
                            detail = "{\"cancelled\":true}";
                        } else if ("arm_late_callback".equals(phase)) {
                            if (e2eLifecycle != null) return;
                            releaseE2eCallback();
                            final long generation = ++e2eArmGeneration;
                            e2eDelayNextCallback = true;
                            e2eLateReleaseRejected = false;
                            e2eHandler.postDelayed(() -> {
                                if (generation != e2eArmGeneration) return;
                                e2eDelayNextCallback = false;
                                releaseE2eCallback();
                            }, 8000);
                            detail = "{\"armed\":true}";
                        } else if ("release_late_callback".equals(phase)) {
                            detail = releaseE2eCallback();
                        } else if ("template_a_b_a".equals(phase)) {
                            startE2eNativeLifecycle(runId, phase);
                            return;
                        } else {
                            detail = e2eSnapshot();
                        }
                    }
                    SelfCheckRegistry.markSuppressed(FEATURE_E2E, "e2e:me:" + runId + ":" + phase, detail);
                } catch (Throwable failure) {
                    SelfCheckRegistry.markStatus(FEATURE_E2E, "failed", "e2e:me:" + runId + ":" + phase,
                            "", failure.getClass().getSimpleName());
                }
            }
        };
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
                new android.content.IntentFilter(E2E_ACTION), androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
    }

    private void touchE2eRun() {
        e2eRunDeadline = android.os.SystemClock.elapsedRealtime() + 60000;
        final long generation = ++e2eRunGeneration;
        e2eHandler.postDelayed(new Runnable() {
            @Override public void run() {
                if (generation != e2eRunGeneration) return;
                if (!debugPropertyEnabled() || android.os.SystemClock.elapsedRealtime() >= e2eRunDeadline) {
                    resetE2eState();
                } else {
                    e2eHandler.postDelayed(this, 500);
                }
            }
        }, 500);
    }

    private void resetE2eState() {
        e2eActiveRunId = "";
        e2eRunGeneration++;
        e2eArmGeneration++;
        e2eDelayNextCallback = false;
        E2eNativeLifecycle lifecycle = e2eLifecycle;
        e2eLifecycle = null;
        if (lifecycle != null) lifecycle.cancel();
        releaseE2eCallback();
        synchronized (e2eLock) {
            e2eEvents.clear();
            e2eNativeBinds.clear();
        }
        e2eExclusiveView = null;
        e2eLifecycleComplete = false;
        e2eLifecyclePassed = false;
        e2eLateReleaseRejected = false;
        e2eLifecycleError = "";
        e2eLifecycleBlocker = "";
        e2eLifecycleEvidence = new JSONObject();
    }

    private String releaseE2eCallback() {
        Runnable callback = e2eDelayedCallback;
        E2eTemplateEvent event = e2eDelayedEvent;
        e2eDelayedCallback = null;
        e2eDelayedEvent = null;
        if (callback == null || event == null) return "{\"pending\":false,\"guard_rejected\":false}";
        callback.run();
        e2eLateReleaseRejected = event.callbackCompleted && event.bindingRejected;
        return "{\"pending\":true,\"guard_rejected\":" + e2eLateReleaseRejected + "}";
    }

    private String e2eSnapshot() throws org.json.JSONException {
        View view = null;
        synchronized (e2eLock) {
            for (int i = e2eEvents.size() - 1; i >= 0; i--) {
                E2eTemplateEvent event = e2eEvents.get(i);
                if (event.zstyle && event.binding == templateBinds.get(event.template)
                        && meTemplateWrapper(event.template) == event.wrapper && event.wrapper.isAttachedToWindow()) {
                    view = event.wrapper;
                    break;
                }
            }
        }
        if (e2eExclusiveView != null && e2eExclusiveView.get() != null
                && e2eExclusiveView.get().isAttachedToWindow()) view = e2eExclusiveView.get();
        JSONObject result = new JSONObject();
        result.put("zstyle_marker", view != null);
        result.put("hide_zstyle", hideZStyle);
        result.put("collapsed", view != null && view.getVisibility() == View.GONE && height(view) == 0);
        result.put("height", height(view));
        result.put("measured_height", view == null ? -1 : view.getHeight());
        result.put("visibility", view == null ? -1 : view.getVisibility());
        result.put("shown", view != null && view.isShown());
        result.put("attached", view != null && view.isAttachedToWindow());
        result.put("template_a_b_a", e2eLifecycleComplete && e2eLifecyclePassed);
        result.put("late_callback_rejected", e2eLateReleaseRejected);
        result.put("lifecycle_complete", e2eLifecycleComplete);
        result.put("lifecycle_passed", e2eLifecyclePassed);
        result.put("lifecycle_error", e2eLifecycleError);
        result.put("lifecycle_blocker", e2eLifecycleBlocker);
        result.put("evidence", e2eLifecycleEvidence);
        return result.toString();
    }

    private static int height(View view) {
        return view == null || view.getLayoutParams() == null ? Integer.MIN_VALUE : view.getLayoutParams().height;
    }

    private static final class E2eTemplateEvent {
        final View template;
        final View wrapper;
        final boolean zstyle;
        final String knownMarker;
        final E2eNativeBind nativeBind;
        final Object binding;
        final int nativeVisibility;
        final int nativeHeight;
        boolean callbackCompleted;
        boolean bindingRejected;
        boolean currentCallbackApplied;

        E2eTemplateEvent(View template, View wrapper, String rendered, E2eNativeBind nativeBind, Object binding) {
            this.template = template;
            this.wrapper = wrapper;
            this.zstyle = containsZStyleMarker(rendered);
            this.knownMarker = knownNonZstyleMarker(rendered);
            this.nativeBind = nativeBind;
            this.binding = binding;
            nativeVisibility = wrapper.getVisibility();
            nativeHeight = height(wrapper);
        }
    }

    private static String knownNonZstyleMarker(String rendered) {
        if (rendered == null || containsZStyleMarker(rendered)) return "";
        String text = rendered.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("my documents") || text.contains("cloud của tôi")) return "documents";
        if (containsZCloudMarker(rendered, "")) return "zcloud";
        if (containsQrWalletMarker(rendered, "")) return "qr_wallet";
        if (containsZBusinessMarker(rendered, "")) return "zbusiness";
        return "";
    }

    private static final class E2eNativeBind {
        final Method method;
        final Object[] args;
        final int priorVisibility;
        final int priorHeight;
        final int restoredVisibility;
        final int restoredHeight;
        final boolean restoredCollapse;

        E2eNativeBind(Method method, Object[] args, View wrapper, int[] prior) {
            this.method = method;
            this.args = args.clone();
            priorVisibility = prior == null ? -1 : prior[0];
            priorHeight = prior == null ? Integer.MIN_VALUE : prior[1];
            restoredVisibility = wrapper.getVisibility();
            restoredHeight = height(wrapper);
            restoredCollapse = prior != null;
        }

        void replay(View template) throws ReflectiveOperationException {
            if (!method.getDeclaringClass().isInstance(template)) throw new IllegalArgumentException("incompatible_native_payload");
            method.setAccessible(true);
            method.invoke(template, args.clone());
        }
    }

    /** Replays captured native F payloads on A's real wrapper. B must have a known non-zStyle marker. */
    private void startE2eNativeLifecycle(String runId, String phase) throws org.json.JSONException {
        if (e2eLifecycle != null) return;
        E2eTemplateEvent a = null;
        E2eTemplateEvent b = null;
        synchronized (e2eLock) {
            for (E2eTemplateEvent event : e2eEvents) {
                if (event.nativeBind == null) continue;
                if (event.zstyle && event.binding == templateBinds.get(event.template)
                        && meTemplateWrapper(event.template) == event.wrapper) a = event;
                else if (!event.knownMarker.isEmpty()) b = event;
            }
        }
        String target = "e2e:me:" + runId + ":" + phase;
        e2eLifecycleComplete = false;
        e2eLifecyclePassed = false;
        e2eLifecycleError = "";
        e2eLifecycleBlocker = "";
        e2eLifecycleEvidence = new JSONObject();
        if (!hideZStyle || a == null || b == null || !a.wrapper.isAttachedToWindow()) {
            e2eLifecycleComplete = true;
            e2eLifecycleBlocker = !hideZStyle ? "hide_zstyle_required" :
                    a == null ? "native_zstyle_payload_unavailable" :
                    b == null ? "known_non_zstyle_native_payload_unavailable" : "native_a_wrapper_detached";
            SelfCheckRegistry.markSuppressed(FEATURE_E2E, target, e2eSnapshot());
            return;
        }
        releaseE2eCallback();
        e2eDelayNextCallback = false;
        e2eLifecycle = new E2eNativeLifecycle(a, b, runId, target);
        e2eLifecycle.run();
    }

    private final class E2eNativeLifecycle implements Runnable {
        final E2eTemplateEvent originalA;
        final E2eTemplateEvent originalB;
        final String runId;
        final String target;
        final long deadline = android.os.SystemClock.elapsedRealtime() + 8000;
        int step;
        Object priorBinding;
        E2eTemplateEvent currentA;
        E2eTemplateEvent heldA;
        E2eTemplateEvent currentB;
        E2eNativeBind directRestoration;
        int bVisibility;
        int bHeight;
        int bMeasuredHeight;
        boolean changed;
        boolean finished;

        E2eNativeLifecycle(E2eTemplateEvent a, E2eTemplateEvent b, String runId, String target) {
            originalA = a;
            originalB = b;
            this.runId = runId;
            this.target = target;
        }

        @Override public void run() {
            if (finished) return;
            if (!e2eObserving() || !runId.equals(e2eActiveRunId)) { cancel(); return; }
            try {
                if (!originalA.wrapper.isAttachedToWindow()
                        || meTemplateWrapper(originalA.template) != originalA.wrapper) {
                    finish("native_a_wrapper_changed"); return;
                }
                if (step == 0) {
                    priorBinding = templateBinds.get(originalA.template);
                    changed = true;
                    originalA.nativeBind.replay(originalA.template);
                    step = 1;
                } else if (step == 1) {
                    currentA = freshEvent(true, priorBinding);
                    if (currentA != null && currentA.callbackCompleted) {
                        if (!currentA.currentCallbackApplied || originalA.wrapper.getVisibility() != View.GONE
                                || height(originalA.wrapper) != 0) { finish("current_a_callback_did_not_collapse"); return; }
                        priorBinding = currentA.binding;
                        // First exercise B directly from collapsed A. Holding another A first
                        // would restore A-to-A geometry and leave A-to-B restoration untested.
                        originalB.nativeBind.replay(originalA.template);
                        step = 2;
                    }
                } else if (step == 2) {
                    E2eTemplateEvent directB = freshEvent(false, priorBinding);
                    if (directB != null && directB.callbackCompleted && originalA.wrapper.getHeight() > 0) {
                        directRestoration = directB.nativeBind;
                        if (directRestoration == null || !directRestoration.restoredCollapse
                                || directRestoration.priorVisibility != View.VISIBLE
                                || directRestoration.priorHeight == 0 || directRestoration.priorHeight == Integer.MIN_VALUE
                                || directRestoration.restoredVisibility != directRestoration.priorVisibility
                                || directRestoration.restoredHeight != directRestoration.priorHeight
                                || !directB.knownMarker.equals(originalB.knownMarker)
                                || originalA.wrapper.getVisibility() != View.VISIBLE || height(originalA.wrapper) == 0) {
                            finish("direct_a_to_b_geometry_restore_mismatch"); return;
                        }
                        priorBinding = directB.binding;
                        originalA.nativeBind.replay(originalA.template);
                        step = 3;
                    }
                } else if (step == 3) {
                    currentA = freshEvent(true, priorBinding);
                    if (currentA != null && currentA.callbackCompleted) {
                        if (!currentA.currentCallbackApplied || originalA.wrapper.getVisibility() != View.GONE
                                || height(originalA.wrapper) != 0) { finish("reused_a_callback_did_not_collapse"); return; }
                        priorBinding = currentA.binding;
                        e2eDelayNextCallback = true;
                        originalA.nativeBind.replay(originalA.template);
                        step = 4;
                    }
                } else if (step == 4) {
                    if (e2eDelayedEvent != null && e2eDelayedEvent.template == originalA.template) {
                        heldA = e2eDelayedEvent;
                        if (heldA.binding == priorBinding || heldA.callbackCompleted) { finish("native_a_callback_not_held"); return; }
                        priorBinding = heldA.binding;
                        originalB.nativeBind.replay(originalA.template);
                        step = 5;
                    }
                } else if (step == 5) {
                    currentB = freshEvent(false, priorBinding);
                    if (currentB != null && currentB.callbackCompleted && originalA.wrapper.getHeight() > 0) {
                        E2eNativeBind bind = heldA.nativeBind;
                        if (bind == null || !bind.restoredCollapse || bind.priorVisibility != View.VISIBLE
                                || bind.priorHeight == 0 || bind.priorHeight == Integer.MIN_VALUE
                                || bind.restoredVisibility != bind.priorVisibility || bind.restoredHeight != bind.priorHeight) {
                            finish("native_a_geometry_restore_mismatch"); return;
                        }
                        bVisibility = originalA.wrapper.getVisibility();
                        bHeight = height(originalA.wrapper);
                        bMeasuredHeight = originalA.wrapper.getHeight();
                        if (!currentB.knownMarker.equals(originalB.knownMarker) || currentB.bindingRejected
                                || bVisibility != View.VISIBLE || bHeight == 0
                                || bVisibility != currentB.nativeVisibility || bHeight != currentB.nativeHeight) {
                            finish("native_b_geometry_restore_mismatch"); return;
                        }
                        releaseE2eCallback();
                        if (!heldA.callbackCompleted || !heldA.bindingRejected
                                || originalA.wrapper.getVisibility() != bVisibility || height(originalA.wrapper) != bHeight) {
                            finish("stale_a_callback_changed_native_b"); return;
                        }
                        priorBinding = currentB.binding;
                        originalA.nativeBind.replay(originalA.template);
                        step = 6;
                    }
                } else if (step == 6) {
                    E2eTemplateEvent finalA = freshEvent(true, priorBinding);
                    if (finalA != null && finalA.callbackCompleted) {
                        if (!finalA.currentCallbackApplied || originalA.wrapper.getVisibility() != View.GONE
                                || height(originalA.wrapper) != 0) { finish("final_a_callback_did_not_collapse"); return; }
                        e2eLifecycleEvidence.put("same_wrapper", true);
                        e2eLifecycleEvidence.put("b_marker", currentB.knownMarker);
                        e2eLifecycleEvidence.put("native_visibility", directRestoration.priorVisibility);
                        e2eLifecycleEvidence.put("native_height", directRestoration.priorHeight);
                        e2eLifecycleEvidence.put("restored_visibility", directRestoration.restoredVisibility);
                        e2eLifecycleEvidence.put("restored_height", directRestoration.restoredHeight);
                        e2eLifecycleEvidence.put("b_visibility", bVisibility);
                        e2eLifecycleEvidence.put("b_height", bHeight);
                        e2eLifecycleEvidence.put("b_measured_height", bMeasuredHeight);
                        e2eLifecycleEvidence.put("native_restoration_verified", true);
                        e2eLifecycleEvidence.put("direct_a_to_b_verified", true);
                        e2eLifecycleEvidence.put("current_callback_applied", true);
                        e2eLifecycleEvidence.put("stale_callback_rejected", true);
                        e2eLifecyclePassed = true;
                        finish(""); return;
                    }
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) { finish("native_rebind_timeout_step_" + step); return; }
                e2eHandler.postDelayed(this, 50);
            } catch (Throwable failure) {
                finish("native_rebind_exception_" + failure.getClass().getSimpleName());
            }
        }

        private E2eTemplateEvent freshEvent(boolean zstyle, Object previous) {
            synchronized (e2eLock) {
                for (int i = e2eEvents.size() - 1; i >= 0; i--) {
                    E2eTemplateEvent event = e2eEvents.get(i);
                    if (event.template == originalA.template && event.wrapper == originalA.wrapper
                            && event.binding != previous && event.binding == templateBinds.get(event.template)) {
                        return event.zstyle == zstyle ? event : null;
                    }
                }
            }
            return null;
        }

        void cancel() {
            if (finished) return;
            finished = true;
            e2eHandler.removeCallbacks(this);
            e2eDelayNextCallback = false;
            if (changed) {
                try { originalA.nativeBind.replay(originalA.template); }
                catch (Throwable ignored) { /* Recovery restarts Zalo after probe cancellation. */ }
            }
            releaseE2eCallback();
            if (e2eLifecycle == this) e2eLifecycle = null;
        }

        private void finish(String error) {
            if (finished) return;
            if (!error.isEmpty()) cancel();
            else {
                finished = true;
                e2eHandler.removeCallbacks(this);
                e2eDelayNextCallback = false;
                releaseE2eCallback();
                e2eLifecycle = null;
            }
            e2eLifecycleError = error;
            e2eLifecycleComplete = true;
            try { SelfCheckRegistry.markSuppressed(FEATURE_E2E, target, e2eSnapshot()); }
            catch (org.json.JSONException ignored) { }
        }
    }

    private static boolean debugPropertyEnabled() {
        String value = debugProperty("debug.zalopatch");
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    private static String debugProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            return (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return "";
        }
    }

    private void hookCurrentTabMeBuilder() throws Throwable {
        Class<?> tabMeClass = XpReflect.findClass(tabMeClass(), classLoader);
        int hooked = XpHooks.hookAllMethods(FEATURE_ITEMS, tabMeClass, currentBuilderMethod(),
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (param.getResult() instanceof java.util.List) {
                    lastBuiltItems = (java.util.List<?>) param.getResult();
                }
                Object filtered = filterIfNeeded(param.getResult());
                if (filtered != param.getResult()) {
                    param.setResult(filtered);
                }
            }
        }).size();
        if (hooked == 0) {
            throw new NoSuchMethodError(tabMeClass() + "#" + currentBuilderMethod());
        }
        if (hideQrWallet || hideZCloud || hideZStyle || hideZBusiness) {
            SelfCheckRegistry.markInstalled(FEATURE_REFRESH,
                    tabMeClass() + "#" + currentBuilderMethod(), hooked);
        }
    }

    private void hookVisibleRowText() {
        XpHooks.hookAllMethods(FEATURE_VISIBLE_ROWS, TextView.class, "setText",
                new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!(param.thisObject instanceof TextView)) {
                    return;
                }
                TextView textView = (TextView) param.thisObject;
                int reason = visibleTextHideReason(String.valueOf(textView.getText()));
                if (reason == HIDE_NONE) {
                    return;
                }
                hideVisibleTextRow(textView, reason);
                textView.post(() -> hideVisibleTextRow(textView, reason));
            }
        });
    }

    private void hookZStyleView() throws Throwable {
        Class<?> viewClass = XpReflect.findClass(zStyleViewClass(), classLoader);
        int hooked = XpHooks.hookAllConstructors(hideZStyle ? FEATURE_ZSTYLE : FEATURE_E2E, viewClass, null,
                new XpHooks.After() {
            @Override public void after(XpHooks.HookParam param) {
                if (!(param.thisObject instanceof View)) return;
                View view = (View) param.thisObject;
                if (hideZStyle) collapseZStyleView(view);
                view.post(() -> {
                    if (hideZStyle) collapseZStyleView(view);
                    if (e2eObserving()) e2eExclusiveView = new java.lang.ref.WeakReference<>(view);
                });
            }
        }).size();
        if (hooked == 0) throw new NoSuchMethodError(zStyleViewClass() + "#<init>");
    }

    private void hookGenericMeTemplate() throws Throwable {
        Class<?> layout = XpReflect.findClass("com.zing.zalo.zinstant.ZaloZinstantCommonLayout", classLoader);
        String owner = hideZStyle ? FEATURE_ZSTYLE : FEATURE_E2E;
        XpHooks.hookAllMethods(owner, layout, "F", new XpHooks.Before() {
            @Override public void before(XpHooks.HookParam param) {
                View wrapper = meTemplateWrapper(param.thisObject);
                if (wrapper == null) return;
                templateBinds.put((View) param.thisObject, new Object());
                int[] prior = e2eObserving() ? collapsedTemplates.get(wrapper) : null;
                if (prior != null) prior = prior.clone();
                restoreMeTemplate(wrapper);
                if (e2eObserving() && param.method instanceof Method) {
                    e2eNativeBinds.put((View) param.thisObject,
                            new E2eNativeBind((Method) param.method, param.args, wrapper, prior));
                }
            }
        }, null);
        XpHooks.hookAllMethods(owner, layout, "setZinstantRootView", new XpHooks.After() {
            @Override public void after(XpHooks.HookParam param) {
                View wrapper = meTemplateWrapper(param.thisObject);
                if (wrapper == null || param.args.length == 0 || param.args[0] == null) return;
                View template = (View) param.thisObject;
                Object renderedRoot = param.args[0];
                Object binding = templateBinds.get(template);
                E2eTemplateEvent captured = null;
                if (e2eObserving()) {
                    String rendered = "";
                    try { rendered = ZinstantRenderedText.read(renderedRoot); }
                    catch (RuntimeException ignored) { }
                    captured = new E2eTemplateEvent(template, wrapper, rendered, e2eNativeBinds.get(template), binding);
                    synchronized (e2eLock) {
                        e2eEvents.add(captured);
                        while (e2eEvents.size() > 32) e2eEvents.remove(0);
                    }
                }
                final E2eTemplateEvent event = captured;
                // OFF mode adds only a bounded debug observation and never changes geometry.
                Runnable callback = () -> {
                    try {
                        if (templateBinds.get(template) != binding || meTemplateWrapper(template) != wrapper) {
                            if (event != null && e2eObserving()) event.bindingRejected = true;
                            return;
                        }
                        if (!hideZStyle) return;
                        String rendered = "";
                        try {
                            Object tree = template.getClass().getMethod("getZinstantRootTree").invoke(template);
                            if (tree != renderedRoot) return;
                            rendered = ZinstantRenderedText.read(tree);
                        } catch (ReflectiveOperationException | RuntimeException ignored) {
                            // A changed host tree leaves the shared template visible.
                        }
                        if (!containsZStyleMarker(visibleText(wrapper)) && !containsZStyleMarker(rendered)) {
                            restoreMeTemplate(wrapper);
                            return;
                        }
                        ViewGroup.LayoutParams params = wrapper.getLayoutParams();
                        if (!collapsedTemplates.containsKey(wrapper)) {
                            collapsedTemplates.put(wrapper, new int[]{wrapper.getVisibility(),
                                    params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height});
                        }
                        collapseView(wrapper);
                        if (event != null && e2eObserving()) event.currentCallbackApplied = true;
                        reportSuppressed(HIDE_ZSTYLE, "LayoutZinstantTabMe rendered marker", "zstyle=1");
                    } finally {
                        if (event != null && e2eObserving()) event.callbackCompleted = true;
                    }
                };
                if (event != null && event.zstyle && hideZStyle && e2eObserving() && e2eDelayNextCallback) {
                    e2eDelayNextCallback = false;
                    e2eDelayedCallback = callback;
                    e2eDelayedEvent = event;
                    return;
                }
                if (hideZStyle || event != null) wrapper.post(callback);
            }
        });
    }

    private void restoreMeTemplate(View wrapper) {
        int[] prior = wrapper == null ? null : collapsedTemplates.remove(wrapper);
        if (prior == null) return;
        wrapper.setVisibility(prior[0]);
        ViewGroup.LayoutParams params = wrapper.getLayoutParams();
        if (params != null) {
            params.height = prior[1];
            wrapper.setLayoutParams(params);
        }
    }

    private static View meTemplateWrapper(Object value) {
        if (!(value instanceof View) || !"com.zing.zalo.uicontrol.zinstant.ZinstantTabMeItem"
                .equals(value.getClass().getName())) return null;
        android.view.ViewParent parent = ((View) value).getParent();
        for (int depth = 0; parent instanceof View && depth < 8; depth++) {
            View view = (View) parent;
            if ("com.zing.zalo.ui.maintab.me.LayoutZinstantTabMe".equals(view.getClass().getName())) return view;
            parent = view.getParent();
        }
        return null;
    }

    private void collapseZStyleView(View view) {
        collapseView(view);
        if (e2eObserving()) e2eExclusiveView = new java.lang.ref.WeakReference<>(view);
        reportSuppressed(HIDE_ZSTYLE, zStyleViewClass(), "zstyle=1");
    }

    private boolean e2eObserving() {
        return !e2eActiveRunId.isEmpty() && debugPropertyEnabled()
                && android.os.SystemClock.elapsedRealtime() < e2eRunDeadline;
    }

    private void hideVisibleTextRow(TextView textView, int reason) {
        if (!hasAncestorClass(textView, tabMeClass())) {
            return;
        }
        View row = findClickableAncestor(textView);
        if (row == null || row.getVisibility() == View.GONE) {
            return;
        }
        collapseView(row);
        reportSuppressed(reason, "TabMe visible text", reasonDetail(reason));
    }

    private static boolean hasAncestorClass(View view, String className) {
        if (view == null || className == null || className.isEmpty()) {
            return false;
        }
        android.view.ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 24) {
            View parentView = (View) parent;
            if (className.equals(parentView.getClass().getName())) {
                return true;
            }
            parent = parentView.getParent();
        }
        return false;
    }

    private void markItemStatus(String feature, boolean enabled, String label) {
        if (enabled) {
            SelfCheckRegistry.markInstalled(feature, "TabMe cleanup rule", 1);
        } else {
            SelfCheckRegistry.markDisabled(feature, label);
        }
    }

    private boolean filterListField(Object owner) throws Throwable {
        for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(owner);
                if (!looksLikeTabMeItemList(value)) {
                    continue;
                }
                Object filtered = filterIfNeeded(value);
                if (filtered != value) {
                    field.set(owner, filtered);
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksLikeTabMeItemList(Object value) {
        if (!(value instanceof List)) {
            return false;
        }
        List<?> list = (List<?>) value;
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            if (isObservedOrShapedItem(item)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Item identity without a mapped item class: classes observed as products of the
     * hooked builder are exact; otherwise an item-shape predicate (Zalo class with a
     * String field plus an int/enum field, matching id+title+summary structure).
     */
    private static final java.util.Set<String> OBSERVED_ITEM_CLASSES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * Per-process Me anchors + resolved handles. Schema names, ids, and marker lists are
     * read once; per-item reflection then uses cached handles. The overlay is adopted
     * before features hook, so these are stable for the process.
     */
    private static final Object ME_ANCHORS_LOCK = new Object();
    private static volatile MeAnchors ME_ANCHORS;
    private static final java.util.Map<String, Field> ME_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> ME_FIELD_MISSES =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final java.util.Map<String, Boolean> ME_SHAPES =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Field[]> ME_STRING_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class MeAnchors {
        final String idField;
        final String idValueField;
        final String titleField;
        final String summaryField;
        final int qrId;
        final int cloudId;
        final java.util.List<String> zstyleMarkers;
        final java.util.List<String> qrWalletMarkers;
        final java.util.List<String> zcloudMarkers;
        final java.util.List<String> zbusinessMarkers;

        private MeAnchors() {
            idField = settingIdField();
            idValueField = settingIdValueField();
            titleField = settingTitleField();
            summaryField = settingSummaryField();
            qrId = qrWalletItemId();
            cloudId = zCloudItemId();
            zstyleMarkers = markerList("features.me_cleanup.zstyle.text_markers",
                    "zstyle", "z style", "music library", "background and music library");
            qrWalletMarkers = markerList("features.me_cleanup.qr_wallet.text_markers",
                    "qr wallet", "qr code", "my qr", "mã qr", "vi qr");
            zcloudMarkers = markerList("features.me_cleanup.zcloud.text_markers",
                    "zcloud", "z cloud", "zalo cloud", "cloud của tôi", "my cloud");
            zbusinessMarkers = markerList("features.me_cleanup.zbusiness.text_markers",
                    "zbusiness", "z business", "zalo business", "zalo for business",
                    "tài khoản kinh doanh", "tai khoan kinh doanh", "doanh nghiệp", "doanh nghiep");
        }

        private static java.util.List<String> markerList(String path, String... fallback) {
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(
                    SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(), path, fallback)));
        }
    }

    private static MeAnchors meAnchors() {
        MeAnchors cached = ME_ANCHORS;
        if (cached == null) {
            synchronized (ME_ANCHORS_LOCK) {
                cached = ME_ANCHORS;
                if (cached == null) {
                    cached = new MeAnchors();
                    ME_ANCHORS = cached;
                }
            }
        }
        return cached;
    }

    /** Same match rule as the local {@code findField}: first declared field with this name. */
    private static Field meField(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty()) {
            return null;
        }
        String key = owner.getName() + "#" + name;
        Field hit = ME_FIELDS.get(key);
        if (hit != null) {
            return hit;
        }
        if (ME_FIELD_MISSES.contains(key)) {
            return null;
        }
        Field found;
        try {
            found = findField(owner, name);
        } catch (NoSuchFieldException missing) {
            ME_FIELD_MISSES.add(key);
            return null;
        }
        found.setAccessible(true);
        ME_FIELDS.put(key, found);
        return found;
    }

    private static boolean meShape(Class<?> clazz) {
        if (clazz == null) {
            return false;
        }
        Boolean cached = ME_SHAPES.get(clazz.getName());
        if (cached != null) {
            return cached;
        }
        boolean shaped = hasItemShape(clazz);
        ME_SHAPES.put(clazz.getName(), shaped);
        return shaped;
    }

    private static Field[] meStringFields(Class<?> clazz) {
        if (clazz == null) {
            return new Field[0];
        }
        Field[] cached = ME_STRING_FIELDS.get(clazz.getName());
        if (cached != null) {
            return cached;
        }
        java.util.List<Field> strings = new java.util.ArrayList<>();
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                        || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                strings.add(field);
            }
        }
        Field[] resolved = strings.toArray(new Field[0]);
        ME_STRING_FIELDS.put(clazz.getName(), resolved);
        return resolved;
    }

    private static void recordObservedItems(List<?> items) {
        if (items == null) {
            return;
        }
        for (Object item : items) {
            if (item != null) {
                OBSERVED_ITEM_CLASSES.add(item.getClass().getName());
            }
        }
    }

    private static boolean isObservedOrShapedItem(Object item) {
        if (item == null) {
            return false;
        }
        if (OBSERVED_ITEM_CLASSES.contains(item.getClass().getName())) {
            return true;
        }
        return meShape(item.getClass());
    }

    private static boolean hasItemShape(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray()) {
            return false;
        }
        String name = clazz.getName();
        if (name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("androidx.")
                || name.startsWith("kotlin.")) {
            return false;
        }
        boolean strings = false;
        boolean idLike = false;
        for (Class<?> current = clazz; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> type = field.getType();
                if (type == String.class) {
                    strings = true;
                } else if (type == Integer.TYPE || type == Integer.class
                        || (!type.isPrimitive() && type.isEnum())) {
                    idLike = true;
                }
            }
        }
        return strings && idLike;
    }

    /**
     * All String field values of an item, for marker matching when the title and
     * summary field names are unmapped. Reads are fail-soft per field.
     */
    private static String allItemStrings(Object item) {
        if (item == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Field field : meStringFields(item.getClass())) {
            try {
                Object value = field.get(item);
                if (value != null) {
                    text.append(value).append(' ');
                }
            } catch (Throwable ignored) {
            }
        }
        return text.toString();
    }

    private Object filterIfNeeded(Object value) {
        if (!(value instanceof List)) {
            return value;
        }
        List<?> originalItems = (List<?>) value;
        recordObservedItems(originalItems);
        logDebugItems(originalItems);
        List<Object> filteredItems = new ArrayList<>(originalItems.size());
        int removed = 0;
        int qrWalletRemoved = 0;
        int zCloudRemoved = 0;
        int zStyleRemoved = 0;
        int zBusinessRemoved = 0;
        for (Object item : originalItems) {
            int reason = hideReason(item, hideQrWallet, hideZCloud, hideZStyle, hideZBusiness);
            if (reason != HIDE_NONE) {
                removed++;
                if (reason == HIDE_QR_WALLET) {
                    qrWalletRemoved++;
                } else if (reason == HIDE_ZCLOUD) {
                    zCloudRemoved++;
                } else if (reason == HIDE_ZSTYLE) {
                    zStyleRemoved++;
                } else if (reason == HIDE_ZBUSINESS) {
                    zBusinessRemoved++;
                }
            } else {
                filteredItems.add(item);
            }
        }
        if (removed > 0 && loggedOnce.compareAndSet(false, true)) {
            log("TabMe items filtered -> kept=" + filteredItems.size() + ", removed=" + removed);
        }
        if (removed > 0) {
            if (qrWalletRemoved > 0) {
                reportSuppressed(HIDE_QR_WALLET, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " qr=" + qrWalletRemoved);
            }
            if (zCloudRemoved > 0) {
                reportSuppressed(HIDE_ZCLOUD, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zcloud=" + zCloudRemoved);
            }
            if (zStyleRemoved > 0) {
                reportSuppressed(HIDE_ZSTYLE, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zstyle=" + zStyleRemoved);
            }
            if (zBusinessRemoved > 0) {
                reportSuppressed(HIDE_ZBUSINESS, "TabMe item list",
                        "kept=" + filteredItems.size() + " removed=" + removed + " zbusiness=" + zBusinessRemoved);
            }
        }
        return removed > 0 ? filteredItems : value;
    }

    private void reportSuppressed(int reason, String target, String detail) {
        String feature = featureForReason(reason);
        AtomicBoolean reported = reportedFlag(reason);
        if (feature == null || reported == null || !reported.compareAndSet(false, true)) {
            return;
        }
        SelfCheckRegistry.markSuppressed(feature, target, detail);
    }

    private static String featureForReason(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return FEATURE_QR_WALLET;
        }
        if (reason == HIDE_ZCLOUD) {
            return FEATURE_ZCLOUD;
        }
        if (reason == HIDE_ZSTYLE) {
            return FEATURE_ZSTYLE;
        }
        if (reason == HIDE_ZBUSINESS) {
            return FEATURE_ZBUSINESS;
        }
        return null;
    }

    private AtomicBoolean reportedFlag(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return reportedQrWallet;
        }
        if (reason == HIDE_ZCLOUD) {
            return reportedZCloud;
        }
        if (reason == HIDE_ZSTYLE) {
            return reportedZStyle;
        }
        if (reason == HIDE_ZBUSINESS) {
            return reportedZBusiness;
        }
        return null;
    }

    private VisibleRemoval hideVisibleRows(View root) {
        VisibleRemoval removal = new VisibleRemoval();
        hideVisibleRows(root, removal);
        return removal;
    }

    private void hideVisibleRows(View view, VisibleRemoval removal) {
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        int reason = visibleRowHideReason(group);
        if (reason != HIDE_NONE) {
            collapseView(group);
            removal.removed++;
            if (reason == HIDE_QR_WALLET) {
                removal.qrWallet++;
            } else if (reason == HIDE_ZCLOUD) {
                removal.zCloud++;
            } else if (reason == HIDE_ZSTYLE) {
                removal.zStyle++;
            } else if (reason == HIDE_ZBUSINESS) {
                removal.zBusiness++;
            }
            return;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            hideVisibleRows(group.getChildAt(i), removal);
        }
    }

    private int visibleRowHideReason(ViewGroup group) {
        if (group.getVisibility() == View.GONE || !group.isClickable()) {
            return HIDE_NONE;
        }
        String text = visibleText(group);
        if (hideQrWallet && containsQrWalletMarker(text, "")) {
            return HIDE_QR_WALLET;
        }
        if (hideZCloud && containsZCloudMarker(text, "")) {
            return HIDE_ZCLOUD;
        }
        if (hideZStyle && containsZStyleMarker(text)) {
            return HIDE_ZSTYLE;
        }
        if (hideZBusiness && containsZBusinessMarker(text, "")) {
            return HIDE_ZBUSINESS;
        }
        return HIDE_NONE;
    }

    private int visibleTextHideReason(String text) {
        if (hideQrWallet && containsQrWalletMarker(text, "")) {
            return HIDE_QR_WALLET;
        }
        if (hideZCloud && containsZCloudMarker(text, "")) {
            return HIDE_ZCLOUD;
        }
        if (hideZStyle && containsZStyleMarker(text)) {
            return HIDE_ZSTYLE;
        }
        if (hideZBusiness && containsZBusinessMarker(text, "")) {
            return HIDE_ZBUSINESS;
        }
        return HIDE_NONE;
    }

    private static View findClickableAncestor(View view) {
        android.view.ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View && depth++ < 8) {
            View parentView = (View) parent;
            if (parentView.isClickable()) {
                return parentView;
            }
            parent = parentView.getParent();
        }
        return null;
    }

    private static String reasonDetail(int reason) {
        if (reason == HIDE_QR_WALLET) {
            return "qr=1";
        }
        if (reason == HIDE_ZCLOUD) {
            return "zcloud=1";
        }
        if (reason == HIDE_ZSTYLE) {
            return "zstyle=1";
        }
        if (reason == HIDE_ZBUSINESS) {
            return "zbusiness=1";
        }
        return "";
    }

    private static String visibleText(View view) {
        StringBuilder builder = new StringBuilder();
        collectVisibleText(view, builder);
        return builder.toString();
    }

    private static void collectVisibleText(View view, StringBuilder builder) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(text);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectVisibleText(group.getChildAt(i), builder);
            }
        }
    }

    private void logDebugItems(List<?> items) {
        if (!HookConfig.isDebugEnabled() || !debugLoggedOnce.compareAndSet(false, true)) {
            return;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("TabMe item snapshot count=").append(items.size());
        int index = 0;
        for (Object item : items) {
            builder.append(" | ").append(index++).append(":").append(describeItem(item));
        }
        log(builder.toString());
    }

    private static final int HIDE_NONE = 0;
    private static final int HIDE_QR_WALLET = 1;
    private static final int HIDE_ZCLOUD = 2;
    private static final int HIDE_ZSTYLE = 3;
    private static final int HIDE_ZBUSINESS = 4;

    private static int hideReason(Object item, boolean hideQrWallet, boolean hideZCloud, boolean hideZStyle, boolean hideZBusiness) {
        if (item == null) {
            return HIDE_NONE;
        }
        // Runtime-derived route only: observed builder products or item-shaped
        // classes are classified by markers/ids. Pinned item classes and the
        // legacy branch for unsupported releases are retired.
        if (isObservedOrShapedItem(item)) {
            return currentSettingHideReason(item, hideQrWallet, hideZCloud, hideZStyle, hideZBusiness);
        }
        return HIDE_NONE;
    }

    private static int currentSettingHideReason(Object item, boolean hideQrWallet, boolean hideZCloud, boolean hideZStyle, boolean hideZBusiness) {
        try {
            // ID extraction is optional: a renamed or missing id field must not disable
            // the marker fallback below (F6). Unknown sentinels are never compared.
            int id = currentSettingIdOrSentinel(item);
            MeAnchors anchors = meAnchors();
            if (id != ID_UNKNOWN) {
                int qrId = anchors.qrId;
                if (hideQrWallet && qrId != ID_UNKNOWN && id == qrId) {
                    return HIDE_QR_WALLET;
                }
                int cloudId = anchors.cloudId;
                if (hideZCloud && cloudId != ID_UNKNOWN && id == cloudId) {
                    return HIDE_ZCLOUD;
                }
            }
            String title = titleOrScanned(item);
            String desc = summaryOrScanned(item);
            if (hideQrWallet && containsQrWalletMarker(title, desc)) {
                return HIDE_QR_WALLET;
            }
            if (hideZCloud && containsZCloudMarker(title, desc)) {
                return HIDE_ZCLOUD;
            }
            if (hideZStyle && (containsZStyleMarker(title) || containsZStyleMarker(desc))) {
                return HIDE_ZSTYLE;
            }
            if (hideZBusiness && containsZBusinessMarker(title, desc)) {
                return HIDE_ZBUSINESS;
            }
            return HIDE_NONE;
        } catch (Throwable throwable) {
            return HIDE_NONE;
        }
    }

    private static final int ID_UNKNOWN = Integer.MIN_VALUE;

    private static int currentSettingIdOrSentinel(Object item) {
        try {
            MeAnchors anchors = meAnchors();
            Object idEnum = objectField(item, anchors.idField);
            Object idValue = objectField(idEnum, anchors.idValueField);
            return idValue instanceof Integer ? (Integer) idValue : ID_UNKNOWN;
        } catch (Throwable ignored) {
            return ID_UNKNOWN;
        }
    }

    /**
     * Title/summary through mapped fields when present, otherwise a scan of every
     * String field. Id-based hiding still runs first when the id fields resolve.
     */
    private static String titleOrScanned(Object item) {
        String field = meAnchors().titleField;
        if (field != null && !field.isEmpty()) {
            try {
                return stringField(item, field);
            } catch (Throwable ignored) {
            }
        }
        return allItemStrings(item);
    }

    private static String summaryOrScanned(Object item) {
        String field = meAnchors().summaryField;
        if (field != null && !field.isEmpty()) {
            try {
                return stringField(item, field);
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static String describeItem(Object item) {
        if (item == null) {
            return "null";
        }
        String className = item.getClass().getName();
        if (isObservedOrShapedItem(item)) {
            try {
                MeAnchors anchors = meAnchors();
                return className + "{id=" + getCurrentSettingId(item)
                        + ",title=" + compact(stringField(item, anchors.titleField))
                        + ",desc=" + compact(stringField(item, anchors.summaryField)) + "}";
            } catch (Throwable ignored) {
                return className + "{unreadable}";
            }
        }
        return className;
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.replace('\n', ' ').trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }

    private static void collapseView(View view) {
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (layoutParams != null) {
            layoutParams.height = 0;
            view.setLayoutParams(layoutParams);
        }
    }

    private static final class VisibleRemoval {
        int removed;
        int qrWallet;
        int zCloud;
        int zStyle;
        int zBusiness;
    }

    private static int getCurrentSettingId(Object item) throws Throwable {
        MeAnchors anchors = meAnchors();
        Object idEnum = objectField(item, anchors.idField);
        Object idValue = objectField(idEnum, anchors.idValueField);
        return idValue instanceof Integer ? (Integer) idValue : -1;
    }

    private static boolean containsZStyleMarker(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return containsAll(lower, meAnchors().zstyleMarkers);
    }

    private static boolean containsQrWalletMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAll(text, meAnchors().qrWalletMarkers);
    }

    private static boolean containsZCloudMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAll(text, meAnchors().zcloudMarkers);
    }

    private static boolean containsZBusinessMarker(String title, String desc) {
        String text = ((title == null ? "" : title) + " " + (desc == null ? "" : desc)).toLowerCase();
        return containsAll(text, meAnchors().zbusinessMarkers);
    }

    private static boolean containsAll(String text, java.util.List<String> markers) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : markers) {
            if (!marker.isEmpty() && text.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static String stringField(Object target, String fieldName) throws Throwable {
        Object value = objectField(target, fieldName);
        return value == null ? null : String.valueOf(value);
    }

    private static Object objectField(Object target, String fieldName) throws Throwable {
        if (target == null) {
            return null;
        }
        Field field = meField(target.getClass(), fieldName);
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        return field.get(target);
    }

    private static Field findField(Class<?> startClass, String fieldName) throws NoSuchFieldException {
        for (Class<?> current = startClass; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // Try the parent class below.
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static String tabMeClass() {
        return schemaString("symbols.me.tab_me_class", TAB_ME_CLASS);
    }

    private static String zinstantViewClass() {
        return schemaString("symbols.me.zinstant_view_class", CURRENT_TAB_ME_ZINSTANT_VIEW_CLASS);
    }

    private static String zStyleViewClass() {
        if (!zStyleViewExclusive()) return "";
        return schemaString("symbols.me.zstyle_view_class", "");
    }

    private static boolean zStyleViewExclusive() {
        SymbolSchema.Active active = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
        JSONObject symbols = active.root == null ? null : active.root.optJSONObject("symbols");
        JSONObject me = symbols == null ? null : symbols.optJSONObject("me");
        // Generic server templates cannot be collapsed by constructor identity.
        return me == null || me.optBoolean("zstyle_view_exclusive", true);
    }

    private static String currentBuilderMethod() {
        return schemaString("symbols.me.current_builder_method", "");
    }

    private static String settingIdField() {
        return schemaString("symbols.me.setting_id_field", "");
    }

    private static String settingIdValueField() {
        return schemaString("symbols.me.setting_id_value_field", "");
    }

    private static String settingTitleField() {
        return schemaString("symbols.me.setting_title_field", "");
    }

    private static String settingSummaryField() {
        return schemaString("symbols.me.setting_summary_field", "");
    }

    private static int qrWalletItemId() {
        return SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.me.qr_wallet_item_id", -1);
    }

    private static int zCloudItemId() {
        return SymbolSchema.integer(HookConfig.resolveModuleContextForHooks(),
                "symbols.me.zcloud_item_id", -1);
    }

    private static String schemaString(String path, String fallback) {
        return SymbolSchema.string(HookConfig.resolveModuleContextForHooks(), path, fallback);
    }
}
