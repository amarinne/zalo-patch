package com.ez.zalopatch.xposed.features;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.ez.zalopatch.CallRecordingImportProtocol;
import com.ez.zalopatch.CallRecordingImportReceiver;
import com.ez.zalopatch.CallRecordingNotificationReceiver;
import com.ez.zalopatch.CallRecordingStore;
import com.ez.zalopatch.CallRecordingMetadata;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.ZaloContactResolver;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

/** Default-off native ZRTC audio recorder for one-to-one voice and video calls. */
public final class CallRecordingFeature extends Feature {
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static final String TEMP_DIRECTORY = "zalo_patch_call_recordings";
    private static final String FEATURE_HOOKS = "calls.auto_record.hooks";
    /** Callback-path diagnostic: what fired and why start did or did not happen. */
    private static final String FEATURE_CALLBACKS = "calls.auto_record.callbacks";
    private static final java.util.Set<String> OBSERVED_CALLBACKS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String FEATURE_NATIVE = "calls.auto_record.native";
    private static final String FEATURE_TRANSFER = "calls.auto_record.transfer";
    private static final String FEATURE_STORAGE = "calls.auto_record.storage";
    private static final String FEATURE_NOTIFICATIONS = "calls.auto_record.notifications";
    private static final String FEATURE_VIDEO_STATE = "calls.auto_record.video_state";
    private static final String FEATURE_METADATA = "calls.auto_record.metadata";
    private static final String CALL_CALLBACK = "com.vng.zing.vn.zrtc.CallCallback";
    private static final String PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI";
    private static final long ACTIVITY_FALLBACK_DELAY_MS = 500L;
    private static final int ACTIVITY_FALLBACK_ATTEMPTS = 5;
    private static final long WAV_READY_TIMEOUT_MS = 5_000L;
    private static final long WAV_READY_POLL_MS = 100L;
    /**
     * A native start can land before ZRTC's peer is attached to the live audio path: the
     * recorder file then holds only its 44-byte WAV header for the whole call. One bounded
     * re-attach on the same main-thread boundary recovers that call; a second start is not
     * attempted, so a genuinely dead native path stays visible instead of looping.
     */
    private static final long CAPTURE_PROBE_DELAY_MS = 2_000L;
    /**
     * Ordered call-path evidence for {@code calls.auto_record.callbacks}. The hit set is
     * unordered, so it cannot show whether a start followed the connect edge or a rebind.
     */
    private static final int EVENT_TRACE_LIMIT = 10;
    private static final long EVENT_TRACE_GAP_MS = 5_000L;
    private static final Deque<String> CALL_EVENTS = new ConcurrentLinkedDeque<>();
    private static final AtomicLong LAST_EVENT_AT = new AtomicLong();
    private static volatile long callEpoch;
    private static final long IMPORT_ACK_TIMEOUT_MS = 30_000L;
    private static final long NATIVE_DISPATCH_TIMEOUT_MS = 2_000L;
    private static final String[] CALL_ACTIVITIES = new String[]{
            "zm.voip.ui.incall.ZmInCallActivity"
    };
    private static final Set<String> HOOKED_CALLBACKS =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Map<Object, Session> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<Object, Session>());
    private static final Map<Long, Session> SESSIONS_BY_PEER = new ConcurrentHashMap<>();
    private static final Map<Long, String> CONFIG_PARTNERS = new ConcurrentHashMap<>();
    private static final Map<Long, String> PEER_PARTNERS = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService STOP_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ZaloPatchCallStop");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService FINALIZER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ZaloPatchCallFinalizer");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile Handler mainHandler;
    /** ZRTC's FileRecorder is process-wide even though the JNI entry accepts a peer handle. */
    private static final Object NATIVE_RECORDER_LOCK = new Object();
    private static Session activeNativeSession;
    private static volatile Method recordMethod;
    private static volatile Method isInCallMethod;
    private static final AtomicInteger FINALIZER_ACTIVE = new AtomicInteger();
    /**
     * Cached peer-manager binding. The overlay is adopted once per Zalo process before
     * features hook, so resolving the four {@code symbols.call_recording} anchors on every
     * callback wastes provider reads plus class/method/field scans on the call path.
     * Resolved once here; the per-call rebind then costs three reflective invokes.
     */
    private static final Object PEER_BINDING_LOCK = new Object();
    private static volatile PeerBinding PEER_BINDING;
    private static final AtomicInteger PEER_BINDING_REPORTED = new AtomicInteger();
    private static final AtomicLong SESSION_SEQUENCE = new AtomicLong();

    /**
     * Hot reload guard: true while a recording is capturing or a finalization is
     * queued or running. The entry rejects reload while this returns true because
     * native recorder state and temp files cannot transfer generations safely.
     */
    public static boolean blocksHotReload() {
        if (FINALIZER_ACTIVE.get() > 0) {
            return true;
        }
        synchronized (SESSIONS) {
            for (Session session : SESSIONS.values()) {
                if (session != null && session.started) {
                    return true;
                }
            }
        }
        for (Session session : SESSIONS_BY_PEER.values()) {
            if (session != null && session.started) {
                return true;
            }
        }
        return false;
    }

    /** Stops module-owned executors before the old generation retires. */
    public static void prepareHotReload() {
        STOP_SCHEDULER.shutdownNow();
        FINALIZER.shutdownNow();
    }

    public CallRecordingFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "CallRecording";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_AUTO_RECORD_CALLS)) {
            SelfCheckRegistry.markDisabled(FEATURE_HOOKS, "ZRTC one-to-one call audio recorder");
            SelfCheckRegistry.markDisabled(FEATURE_NATIVE, "ZRTC native WAV recorder");
            SelfCheckRegistry.markDisabled(FEATURE_TRANSFER, "Private WAV transfer acknowledgment");
            SelfCheckRegistry.markDisabled(FEATURE_STORAGE, "MediaStore shared M4A");
            SelfCheckRegistry.markDisabled(FEATURE_NOTIFICATIONS, "Recording status notifications");
            SelfCheckRegistry.markDisabled(FEATURE_VIDEO_STATE, "ZRTC video state");
            SelfCheckRegistry.markDisabled(FEATURE_METADATA, "Zalo contact phone lookup");
            return;
        }

        Class<?> peerClass = XpReflect.findClassIfExists(PEER_JNI, classLoader);
        if (peerClass == null) {
            markStale("PeerJNI missing");
            return;
        }
        try {
            recordMethod = peerClass.getDeclaredMethod("zrtc_peer_start_record_audio",
                    long.class, boolean.class, String.class);
            recordMethod.setAccessible(true);
            isInCallMethod = peerClass.getDeclaredMethod("zrtc_peer_is_in_call", long.class);
            isInCallMethod.setAccessible(true);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                    PEER_JNI + "#zrtc_peer_start_record_audio", throwable);
            return;
        }

        int hooks = hookPeerMetadata(peerClass);
        hooks += hookAudioStreamRegistration(peerClass);
        hooks += hookPeerTermination(peerClass);
        hooks += hookCallbackRegistration(peerClass);
        hooks += hookCallbackBase();
        hooks += hookCurrentCallback();
        hooks += hookActivities();
        if (hooks == 0) {
            markStale("No call lifecycle hooks installed");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE_HOOKS,
                "PeerJNI callback and ZmInCallActivity audio lifecycle", hooks);
        SelfCheckRegistry.markInstalled(FEATURE_NATIVE,
                "PeerJNI#zrtc_peer_start_record_audio", 1);
        SelfCheckRegistry.markInstalled(FEATURE_TRANSFER,
                "CallRecordingImportReceiver private WAV copy", 1);
        SelfCheckRegistry.markInstalled(FEATURE_STORAGE,
                "MediaStore shared M4A finalizer", 1);
        SelfCheckRegistry.markInstalled(FEATURE_VIDEO_STATE,
                "CallCallback video transition observer", 1);
        if (HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS)) {
            SelfCheckRegistry.markInstalled(FEATURE_NOTIFICATIONS,
                    "CallRecordingNotificationReceiver", 1);
        } else {
            SelfCheckRegistry.markDisabled(FEATURE_NOTIFICATIONS,
                    "Recording status notifications");
        }
        SelfCheckRegistry.markInstalled(FEATURE_METADATA,
                "ZRTC partner ID and local contact databases", 1);
        recoverPendingRecordings();
    }

    private int hookCallbackRegistration(Class<?> peerClass) {
        List<XpHooks.Handle> hooks = XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass,
                "zrtc_peer_register_callback", new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
                        if (param.args == null || param.args.length < 2
                                || !(param.args[0] instanceof Long) || param.args[1] == null) {
                            return;
                        }
                        Object callback = param.args[1];
                        if (!isCallCallback(callback.getClass())) {
                            return;
                        }
                        hookCallbackClass(callback.getClass());
                        long peerHandle = (Long) param.args[0];
                        Session session = SESSIONS_BY_PEER.get(peerHandle);
                        if (session == null) {
                            session = new Session(peerHandle, PEER_PARTNERS.get(peerHandle));
                        }
                        SESSIONS.put(callback, session);
                        SESSIONS_BY_PEER.put(peerHandle, session);
                        recordEvent("reg:" + peerTag(peerHandle) + "#"
                                + Integer.toHexString(System.identityHashCode(callback)));
                    }
                });
        return hooks.size();
    }

    private int hookPeerTermination(Class<?> peerClass) {
        int count = 0;
        for (String methodName : new String[]{
                "zrtc_peer_end_call", "zrtc_peer_force_stop", "zrtc_peer_delete"
        }) {
            count += XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass, methodName,
                    new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Long)) {
                        return;
                    }
                    stopForPeer((Long) param.args[0], "PeerJNI#" + methodName);
                }
            }).size();
        }
        return count;
    }

    private int hookPeerMetadata(Class<?> peerClass) {
        int count = 0;
        count += XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass,
                "zrtc_call_config_set_partner_id", new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
                        if (param.args != null && param.args.length >= 2
                                && param.args[0] instanceof Long
                                && param.args[1] instanceof Integer) {
                            long uid = ((Integer) param.args[1]) & 0xffffffffL;
                            if (uid > 0L) {
                                CONFIG_PARTNERS.put((Long) param.args[0], String.valueOf(uid));
                            }
                        }
                    }
                }).size();
        XpHooks.Before bindPeer = new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                if (param.args == null || param.args.length < 2
                        || !(param.args[0] instanceof Long) || !(param.args[1] instanceof Long)) {
                    return;
                }
                String partner = CONFIG_PARTNERS.get((Long) param.args[1]);
                if (partner != null && !partner.isEmpty()) {
                    PEER_PARTNERS.put((Long) param.args[0], partner);
                }
                long peerHandle = (Long) param.args[0];
                Session session = SESSIONS_BY_PEER.get(peerHandle);
                if (session == null) {
                    session = new Session(peerHandle, partner);
                    SESSIONS_BY_PEER.put(peerHandle, session);
                }
                boolean outgoing = "zrtc_peer_make_call".equals(param.method.getName());
                session.direction = outgoing ? "outgoing" : "incoming";
                recordEvent("bind:" + (outgoing ? "out" : "in") + ":" + peerTag(peerHandle));
            }
        };
        count += XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass, "zrtc_peer_make_call", bindPeer).size();
        count += XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass, "zrtc_peer_incoming_call", bindPeer).size();
        return count;
    }

    private int hookAudioStreamRegistration(Class<?> peerClass) {
        int count = 0;
        for (String methodName : new String[]{
                "zrtc_peer_register_in_audio_stream",
                "zrtc_peer_register_out_audio_stream"
        }) {
            count += XpHooks.hookAllMethods(FEATURE_HOOKS, peerClass, methodName,
                    new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Long)) {
                        return;
                    }
                    long peerHandle = (Long) param.args[0];
                    Session session = SESSIONS_BY_PEER.get(peerHandle);
                    if (session == null) {
                        session = new Session(peerHandle, PEER_PARTNERS.get(peerHandle));
                        SESSIONS_BY_PEER.put(peerHandle, session);
                    }
                    synchronized (session) {
                        session.confirmed = true;
                        session.audioConnected = true;
                    }
                    recordEvent("stream:" + methodName.replace("zrtc_peer_register_", "")
                            + ":" + peerTag(peerHandle));
                    start(session, methodName);
                }
            }).size();
        }
        return count;
    }

    private int hookCallbackBase() {
        Class<?> callbackClass = XpReflect.findClassIfExists(CALL_CALLBACK, classLoader);
        return callbackClass == null ? 0 : hookCallbackClass(callbackClass);
    }

    private int hookCurrentCallback() {
        String className = SymbolSchema.stringForHooks(
                HookConfig.resolveModuleContextForHooks(),
                "symbols.call_recording.callback_class", "").value;
        if (className.isEmpty()) return 0;
        Class<?> callbackClass = XpReflect.findClassIfExists(className, classLoader);
        return callbackClass == null ? 0 : hookCallbackClass(callbackClass);
    }

    private int hookCallbackClass(Class<?> callbackClass) {
        int count = 0;
        for (Class<?> current = callbackClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                String name = method.getName();
                if (Modifier.isAbstract(method.getModifiers())
                        || !CallRecordingLifecycle.observes(name)) {
                    continue;
                }
                String signature = current.getName() + "#" + method.toGenericString();
                if (!HOOKED_CALLBACKS.add(signature)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XpHooks.hookMethod(FEATURE_HOOKS, method, callbackHook(method));
                    count++;
                } catch (Throwable throwable) {
                    HOOKED_CALLBACKS.remove(signature);
                    SelfCheckRegistry.markFailed(FEATURE_HOOKS,
                            current.getName() + "#" + name, throwable);
                }
            }
        }
        return count;
    }

    private XpHooks.Before callbackHook(final Method method) {
        return new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                String methodName = method.getName();
                // Record the callback entry itself. Without this a connected call whose
                // session is unbound (or not yet confirmed) leaves hit_count at 0, which is
                // indistinguishable from hooks that never fired.
                int entryState = firstInt(param.args);
                OBSERVED_CALLBACKS.add(methodName
                        + (entryState == Integer.MIN_VALUE ? "" : "(" + entryState + ")"));
                recordEvent(methodName
                        + (entryState == Integer.MIN_VALUE ? "" : "(" + entryState + ")"));
                SelfCheckRegistry.incrementHit(FEATURE_HOOKS,
                        param.thisObject.getClass().getName() + "#" + methodName,
                        "seen=" + String.join(",", OBSERVED_CALLBACKS)
                                + " events=" + eventTrace());
                Session session = SESSIONS.get(param.thisObject);
                // Zalo can reuse its callback after replacing the native peer. A new call
                // must resolve the current handle instead of reviving the retired session.
                if (session == null || CallRecordingLifecycle.beginsCall(methodName)) {
                    Session current = resolveCurrentSession(param.thisObject,
                            CallRecordingLifecycle.beginsCall(methodName));
                    if (current != null) session = current;
                }
                // Older hosts can bind through native callback registration without a
                // mapped Java manager. Preserve that binding only while it is live.
                if (session == null || session.deleted) {
                    SelfCheckRegistry.markStatus(FEATURE_CALLBACKS, "stale", "no bound session",
                            "seen=" + String.join(",", OBSERVED_CALLBACKS)
                                    + " deleted=" + (session != null)
                                    + " events=" + eventTrace(), "");
                    return;
                }
                if ("onIncomingCall".equals(methodName)) {
                    session.direction = "incoming";
                    return;
                }
                if ("onMakeCall".equals(methodName)) {
                    session.direction = "outgoing";
                    return;
                }
                int state = firstInt(param.args);
                if (CallRecordingLifecycle.isVideoState(methodName)) {
                    SelfCheckRegistry.incrementHit(FEATURE_VIDEO_STATE,
                            "CallCallback#onCallVideoState",
                            "state=" + state + " audio_active=" + session.started);
                    return;
                }
                if (CallRecordingLifecycle.shouldStopAudio(methodName, state)) {
                    // ZRTC ignores recordAudio(false, ...) after its controller leaves the
                    // confirmed state. Stop inside this before-hook instead of scheduling work
                    // that may run after the host advances the terminal call state.
                    stop(session, methodName);
                    return;
                }
                boolean shouldStart;
                synchronized (session) {
                    if (CallRecordingLifecycle.confirmsCall(methodName, state)) {
                        session.confirmed = true;
                    }
                    if (CallRecordingLifecycle.connectsAudio(methodName, state)) {
                        session.audioConnected = true;
                    }
                    shouldStart = CallRecordingLifecycle.shouldStartAudio(
                            session.confirmed, session.audioConnected);
                }
                if (shouldStart) {
                    start(session, methodName);
                } else {
                    SelfCheckRegistry.markStatus(FEATURE_CALLBACKS, "installed_no_hits",
                            "awaiting connect",
                            "seen=" + String.join(",", OBSERVED_CALLBACKS)
                                    + " confirmed=" + session.confirmed
                                    + " audio=" + session.audioConnected
                                    + " peer=" + (session.peerHandle != 0L)
                                    + " events=" + eventTrace(), "");
                }
            }
        };
    }

    private static Session resolveCurrentSession(Object callback, boolean refresh) {
        // Stable binding first, except at a new call: Zalo can reuse its callback object after
        // replacing the native peer, and returning the previous call's session would start the
        // recorder on a dead handle. The refresh only replaces the binding when the manager
        // chain resolves a handle; a missing symbol keeps the stable binding.
        Session bound = SESSIONS.get(callback);
        if (bound != null && !refresh) {
            return bound;
        }
        PeerBinding binding = peerBinding(callback == null
                ? null : callback.getClass().getClassLoader());
        if (binding == null || !binding.usable()) {
            return bound;
        }
        try {
            Object manager = binding.accessor.invoke(null);
            Object container = binding.container.get(manager);
            if (container == null) {
                return bound;
            }
            long peerHandle = binding.handle.getLong(container);
            if (peerHandle == 0L) {
                return bound;
            }
            if (bound != null && bound.peerHandle == peerHandle) {
                return bound;
            }
            Session session = SESSIONS_BY_PEER.get(peerHandle);
            if (session == null) {
                session = new Session(peerHandle, PEER_PARTNERS.get(peerHandle));
                SESSIONS_BY_PEER.put(peerHandle, session);
            }
            // Only a handle bound for this call may displace the stable binding; the manager
            // can still expose the previous peer while the new call is being set up.
            if (bound != null && session.sequence <= bound.sequence) {
                return bound;
            }
            SESSIONS.put(callback, session);
            SelfCheckRegistry.incrementHit(FEATURE_HOOKS,
                    binding.manager.getName() + "#" + binding.accessor.getName(),
                    "callback bound to current peer " + peerTag(peerHandle));
            recordEvent("rebind:" + peerTag(peerHandle));
            return session;
        } catch (Throwable throwable) {
            // A drifted or renamed symbol is migration work, not an unexpected hook
            // failure; only a throwing callback belongs in the failed tier. Reported
            // once: the binding is process-static, so per-call reports would spam the
            // provider boundary on every new call.
            if (PEER_BINDING_REPORTED.compareAndSet(0, 1)) {
                if (isMissingSymbol(throwable)) {
                    SelfCheckRegistry.markStale(FEATURE_HOOKS,
                            binding.manager.getName() + " peer handle",
                            throwable.getClass().getSimpleName() + " " + throwable.getMessage());
                } else {
                    SelfCheckRegistry.markFailed(FEATURE_HOOKS,
                            binding.manager.getName() + " peer handle", throwable);
                }
            }
            return bound;
        }
    }

    /**
     * Resolves the obfuscated peer-manager chain once per process and caches the
     * reflective handles. Absent anchors cache an unusable binding internally and
     * return null, so later calls skip the provider reads.
     */
    private static PeerBinding peerBinding(ClassLoader loader) {
        PeerBinding cached = PEER_BINDING;
        if (cached != null) {
            return cached.usable() ? cached : null;
        }
        synchronized (PEER_BINDING_LOCK) {
            cached = PEER_BINDING;
            if (cached != null) {
                return cached.usable() ? cached : null;
            }
            PeerBinding resolved = resolvePeerBinding(loader);
            PEER_BINDING = resolved;
            return resolved.usable() ? resolved : null;
        }
    }

    private static PeerBinding resolvePeerBinding(ClassLoader loader) {
        Context context = HookConfig.resolveModuleContextForHooks();
        String managerClassName = SymbolSchema.stringForHooks(context,
                "symbols.call_recording.peer_manager_class", "").value;
        String instanceMethod = SymbolSchema.stringForHooks(context,
                "symbols.call_recording.peer_manager_instance_method", "").value;
        String containerField = SymbolSchema.stringForHooks(context,
                "symbols.call_recording.peer_container_field", "").value;
        String handleField = SymbolSchema.stringForHooks(context,
                "symbols.call_recording.peer_handle_field", "").value;
        if (managerClassName.isEmpty() || instanceMethod.isEmpty()
                || containerField.isEmpty() || handleField.isEmpty()) {
            return PeerBinding.unusable();
        }
        try {
            Class<?> managerClass = XpReflect.findClass(managerClassName, loader);
            Method accessor = null;
            for (Class<?> current = managerClass; current != null;
                 current = current.getSuperclass()) {
                for (Method candidate : current.getDeclaredMethods()) {
                    if (instanceMethod.equals(candidate.getName())
                            && Modifier.isStatic(candidate.getModifiers())
                            && candidate.getParameterTypes().length == 0
                            && candidate.getReturnType() == managerClass) {
                        accessor = candidate;
                        break;
                    }
                }
                if (accessor != null) {
                    break;
                }
            }
            Field container = null;
            for (Class<?> current = managerClass; current != null;
                 current = current.getSuperclass()) {
                for (Field candidate : current.getDeclaredFields()) {
                    if (containerField.equals(candidate.getName())
                            && !Modifier.isStatic(candidate.getModifiers())) {
                        container = candidate;
                        break;
                    }
                }
                if (container != null) {
                    break;
                }
            }
            Field handle = null;
            if (container != null) {
                for (Class<?> current = container.getType(); current != null;
                     current = current.getSuperclass()) {
                    for (Field candidate : current.getDeclaredFields()) {
                        if (handleField.equals(candidate.getName())
                                && candidate.getType() == Long.TYPE) {
                            handle = candidate;
                            break;
                        }
                    }
                    if (handle != null) {
                        break;
                    }
                }
            }
            if (accessor == null || container == null || handle == null) {
                reportPeerBindingStale(managerClassName, "peer handle shape missing");
                return PeerBinding.unusable();
            }
            accessor.setAccessible(true);
            container.setAccessible(true);
            handle.setAccessible(true);
            return new PeerBinding(managerClass, accessor, container, handle);
        } catch (Throwable throwable) {
            if (isMissingSymbol(throwable)) {
                reportPeerBindingStale(managerClassName,
                        throwable.getClass().getSimpleName() + " " + throwable.getMessage());
            } else if (PEER_BINDING_REPORTED.compareAndSet(0, 1)) {
                SelfCheckRegistry.markFailed(FEATURE_HOOKS,
                        managerClassName + " peer handle", throwable);
            }
            return PeerBinding.unusable();
        }
    }

    private static void reportPeerBindingStale(String managerClassName, String reason) {
        if (PEER_BINDING_REPORTED.compareAndSet(0, 1)) {
            SelfCheckRegistry.markStale(FEATURE_HOOKS, managerClassName + " peer handle", reason);
        }
    }

    private static final class PeerBinding {
        final Class<?> manager;
        final Method accessor;
        final Field container;
        final Field handle;

        PeerBinding(Class<?> manager, Method accessor, Field container, Field handle) {
            this.manager = manager;
            this.accessor = accessor;
            this.container = container;
            this.handle = handle;
        }

        static PeerBinding unusable() {
            return new PeerBinding(null, null, null, null);
        }

        boolean usable() {
            return manager != null && accessor != null && container != null && handle != null;
        }
    }

    /** Reflection miss on a mapped symbol: drift, not a hook defect. */
    private static boolean isMissingSymbol(Throwable throwable) {
        return throwable instanceof NoSuchMethodException
                || throwable instanceof NoSuchFieldException
                || throwable instanceof ClassNotFoundException
                || throwable instanceof NoClassDefFoundError;
    }

    private int hookActivities() {
        int count = 0;
        for (String className : CALL_ACTIVITIES) {
            Class<?> activityClass = XpReflect.findClassIfExists(className, classLoader);
            if (activityClass == null || !Activity.class.isAssignableFrom(activityClass)) {
                continue;
            }
            // Terminal fallback only, with no obfuscated letters: when the call UI is
            // destroyed, stop any started session. The connected edge comes from
            // onCallAudioState==32 and the audio-stream registrations (T1c); the
            // retired U1/K2/n trigger misread a RelativeLayout on 26.09.01.
            count += XpHooks.hookAllMethods(FEATURE_HOOKS, activityClass, "onDestroy",
                    new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    for (Session session : SESSIONS_BY_PEER.values()) {
                        if (session.started) {
                            stop(session, "activity_destroy");
                        }
                    }
                }
            }).size();
        }
        return count;
    }

    // PeerJNI callbacks can arrive on Zalo's WebRTC/audio threads. Zalo's own recording
    // action calls this native entry from the main/UI thread; keep the same thread contract
    // so FileRecorderImpl cannot race its audio mixer while it is being installed or removed.
    private static void start(Session session, String trigger) {
        synchronized (session) {
            if (session.deleted || session.started
                    || !CallRecordingLifecycle.shouldStartAudio(
                    session.confirmed, session.audioConnected)) {
                return;
            }
        }
        runOnMainAndWait(() -> startOnMain(session, trigger),
                "PeerJNI#zrtc_peer_start_record_audio start");
    }

    private static void startOnMain(Session session, String trigger) {
        int attempt;
        synchronized (NATIVE_RECORDER_LOCK) {
            if (activeNativeSession != null && activeNativeSession != session) {
                synchronized (activeNativeSession) {
                    if (activeNativeSession.started) {
                        SelfCheckRegistry.markStatus(FEATURE_NATIVE, "installed_no_hits",
                                "ZRTC native WAV recorder", "trigger=" + trigger,
                                "Another native recording session is active");
                        return;
                    }
                }
                activeNativeSession = null;
            }
            synchronized (session) {
                if (session.deleted || session.started
                        || !CallRecordingLifecycle.shouldStartAudio(
                        session.confirmed, session.audioConnected)) {
                    return;
                }
                Context application = HookConfig.resolveFallbackContextForHooks();
                Method nativeRecord = recordMethod;
                if (application == null || nativeRecord == null) {
                    SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed",
                            "ZRTC native WAV recorder", "trigger=" + trigger,
                            "Application or native method unavailable");
                    return;
                }
                if (session.tempFile == null) {
                    session.startedAt = System.currentTimeMillis();
                    session.pendingName = CallRecordingStore.newPendingName(
                            session.startedAt, session.direction);
                }
                try {
                    File directory = new File(application.getCacheDir(), TEMP_DIRECTORY);
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IllegalStateException("Could not create temporary recording directory");
                    }
                    if (session.tempFile == null) {
                        session.tempFile = new File(directory, session.pendingName);
                    }
                    attempt = ++session.startAttempts;
                    nativeRecord.invoke(null, session.peerHandle, true,
                            session.tempFile.getAbsolutePath());
                    activeNativeSession = session;
                    session.started = true;
                    if (attempt == 1
                            && HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS)) {
                        sendRecordingNotification(application,
                                CallRecordingNotificationReceiver.STATE_RUNNING);
                    }
                    SelfCheckRegistry.incrementHit(FEATURE_NATIVE,
                            "PeerJNI#zrtc_peer_start_record_audio",
                            "start direction=" + session.direction + " trigger=" + trigger
                                    + " media=audio_only attempt=" + attempt
                                    + " peer=" + peerTag(session.peerHandle)
                                    + " events=" + eventTrace());
                    scheduleCaptureProbe(session, attempt);
                } catch (Throwable throwable) {
                    if (session.tempFile != null) {
                        session.tempFile.delete();
                    }
                    SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                            "PeerJNI#zrtc_peer_start_record_audio", unwrap(throwable));
                    return;
                }
            }
        }
    }

    private static void scheduleCaptureProbe(Session session, int attempt) {
        try {
            STOP_SCHEDULER.schedule(() -> probeCapture(session, attempt),
                    CAPTURE_PROBE_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException rejected) {
            // A retired generation already shut the scheduler down; the start itself stands.
        }
    }

    /**
     * A header-only native file after the probe delay means ZRTC created the recorder but never
     * fed it: the peer was not yet attached to the live audio path. Stop that recorder and
     * re-attach once, then keep the result honest in the native row either way.
     */
    private static void probeCapture(Session session, int attempt) {
        long size;
        synchronized (session) {
            if (!session.started || session.startAttempts != attempt || session.tempFile == null) {
                return;
            }
            size = session.tempFile.isFile() ? session.tempFile.length() : 0L;
        }
        String peer = peerTag(session.peerHandle);
        if (CallRecordingLifecycle.hasCaptureAudio(size)) {
            SelfCheckRegistry.markStatus(FEATURE_NATIVE, "active",
                    "PeerJNI#zrtc_peer_start_record_audio",
                    "attempt=" + attempt + " bytes=" + size + " peer=" + peer, "");
            return;
        }
        if (!CallRecordingLifecycle.shouldReattachEmptyCapture(attempt, size)) {
            SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed",
                    "PeerJNI#zrtc_peer_start_record_audio",
                    "attempt=" + attempt + " bytes=" + size + " peer=" + peer,
                    "Native recorder produced no audio after re-attach");
            return;
        }
        recordEvent("probe:" + size + "B");
        runOnMainAndWait(() -> retryCaptureOnMain(session, attempt),
                "PeerJNI#zrtc_peer_start_record_audio re-attach");
    }

    private static void retryCaptureOnMain(Session session, int attempt) {
        synchronized (NATIVE_RECORDER_LOCK) {
            synchronized (session) {
                if (!session.started || session.startAttempts != attempt
                        || activeNativeSession != session || session.tempFile == null) {
                    return;
                }
                Method nativeRecord = recordMethod;
                if (nativeRecord == null) {
                    return;
                }
                File stale = session.tempFile;
                try {
                    nativeRecord.invoke(null, session.peerHandle, false,
                            stale.getAbsolutePath());
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                            "PeerJNI#zrtc_peer_start_record_audio re-attach", unwrap(throwable));
                    return;
                }
                session.startedAt = System.currentTimeMillis();
                session.pendingName = CallRecordingStore.newPendingName(
                        session.startedAt, session.direction);
                File next = new File(stale.getParentFile(), session.pendingName);
                int nextAttempt = ++session.startAttempts;
                try {
                    nativeRecord.invoke(null, session.peerHandle, true, next.getAbsolutePath());
                    session.tempFile = next;
                    stale.delete();
                    recordEvent("retry:" + nextAttempt);
                    SelfCheckRegistry.markStatus(FEATURE_NATIVE, "active",
                            "PeerJNI#zrtc_peer_start_record_audio",
                            "re-attached attempt=" + nextAttempt
                                    + " peer=" + peerTag(session.peerHandle),
                            "");
                    scheduleCaptureProbe(session, nextAttempt);
                } catch (Throwable throwable) {
                    session.tempFile = stale;
                    SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                            "PeerJNI#zrtc_peer_start_record_audio re-attach", unwrap(throwable));
                }
            }
        }
    }

    private static void stop(Session session, String trigger) {
        synchronized (session) {
            if (!session.started) {
                session.confirmed = false;
                session.audioConnected = false;
                return;
            }
        }
        runOnMainAndWait(() -> stopOnMain(session, trigger),
                "PeerJNI#zrtc_peer_start_record_audio stop");
    }

    private static void stopOnMain(Session session, String trigger) {
        Context application;
        File tempFile;
        String pendingName;
        String effectivePeerUid;
        CallRecordingMetadata.Snapshot observed;
        synchronized (NATIVE_RECORDER_LOCK) {
            synchronized (session) {
                if (!session.started) {
                    session.confirmed = false;
                    session.audioConnected = false;
                    return;
                }
                if (activeNativeSession != session) {
                    SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed",
                            "ZRTC native WAV recorder", "trigger=" + trigger,
                            "Native recording session ownership changed before stop");
                    session.started = false;
                    session.confirmed = false;
                    session.audioConnected = false;
                    return;
                }
                recordEvent("stop:" + trigger);
                application = HookConfig.resolveFallbackContextForHooks();
                try {
                    Method nativeRecord = recordMethod;
                    if (nativeRecord != null) {
                        nativeRecord.invoke(null, session.peerHandle, false,
                                session.tempFile == null ? "" : session.tempFile.getAbsolutePath());
                    }
                } catch (Throwable throwable) {
                    SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                            "PeerJNI#zrtc_peer_start_record_audio stop", unwrap(throwable));
                } finally {
                    session.started = false;
                    activeNativeSession = null;
                }
                if (application == null) {
                    return;
                }
                if (HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS)) {
                    sendRecordingNotification(application,
                            CallRecordingNotificationReceiver.STATE_FINALIZING);
                }
                observed = CallRecordingMetadata.current();
                String mappedPeerUid = PEER_PARTNERS.get(session.peerHandle);
                effectivePeerUid = mappedPeerUid == null || mappedPeerUid.isEmpty()
                        ? (session.peerUid == null ? observed.peerUid : session.peerUid)
                        : mappedPeerUid;
                tempFile = session.tempFile;
                pendingName = session.pendingName;
                CallRecordingMetadata.clear();
                // A ZRTC peer handle can survive across calls. Clear per-call state before
                // the next callback reuses this Session; finalizer already owns captured values.
                session.confirmed = false;
                session.audioConnected = false;
                session.tempFile = null;
                session.pendingName = null;
                session.startedAt = 0L;
                session.startAttempts = 0;
                session.direction = "unknown";
            }
        }
        final Context finalApplication = application;
        final File finalTempFile = tempFile;
        final String finalPendingName = pendingName;
        final String finalPeerUid = effectivePeerUid;
        final CallRecordingMetadata.Snapshot finalObserved = observed;
        FINALIZER_ACTIVE.incrementAndGet();
        FINALIZER.execute(() -> {
            try {
                finalizeRecording(finalApplication, finalTempFile,
                        finalPendingName, finalPeerUid, finalObserved, trigger);
            } finally {
                FINALIZER_ACTIVE.decrementAndGet();
            }
        });
    }

    private static void runOnMainAndWait(Runnable action, String operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
            return;
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        Handler handler = mainHandler();
        Runnable dispatch = () -> {
            try {
                if (!cancelled.get()) {
                    action.run();
                }
            } finally {
                completed.countDown();
            }
        };
        if (!handler.post(dispatch)) {
            SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed", operation, "",
                    "Main thread dispatch rejected");
            return;
        }
        try {
            if (!completed.await(NATIVE_DISPATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                cancelled.set(true);
                handler.removeCallbacks(dispatch);
                SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed", operation, "",
                        "Main thread dispatch timed out");
            }
        } catch (InterruptedException interrupted) {
            cancelled.set(true);
            handler.removeCallbacks(dispatch);
            Thread.currentThread().interrupt();
            SelfCheckRegistry.markStatus(FEATURE_NATIVE, "failed", operation, "",
                    "Main thread dispatch interrupted");
        }
    }

    private static Handler mainHandler() {
        Handler handler = mainHandler;
        if (handler != null) {
            return handler;
        }
        synchronized (CallRecordingFeature.class) {
            handler = mainHandler;
            if (handler == null) {
                handler = new Handler(Looper.getMainLooper());
                mainHandler = handler;
            }
            return handler;
        }
    }

    private static void finalizeRecording(
            Context application,
            File tempFile,
            String pendingName,
            String effectivePeerUid,
            CallRecordingMetadata.Snapshot observed,
            String trigger) {
        if (!awaitNativeWav(tempFile)) {
            long size = tempFile == null || !tempFile.isFile() ? 0L : tempFile.length();
            SelfCheckRegistry.markStatus(FEATURE_STORAGE, "failed",
                    "ZRTC native WAV readiness", "trigger=" + trigger + " size=" + size
                            + " events=" + eventTrace(),
                    "Native WAV did not finalize within 5 seconds; private source preserved");
            sendRecordingNotification(application,
                    CallRecordingNotificationReceiver.STATE_FAILED);
            return;
        }
        try {
            ZaloContactResolver.Result contact = ZaloContactResolver.resolve(application,
                    effectivePeerUid,
                    observed.displayName, observed.phoneNumber);
            SelfCheckRegistry.incrementHit(FEATURE_METADATA,
                    "ZRTC partner ID and local contact databases",
                    "uid=" + (effectivePeerUid != null && !effectivePeerUid.isEmpty())
                            + " name=" + !"Zalo contact".equals(contact.displayName)
                            + " phone=" + !contact.phoneNumber.isEmpty());
            boolean queued = enqueueImport(application, tempFile,
                    pendingName, contact.displayName, contact.phoneNumber, trigger);
            if (!queued) {
                SelfCheckRegistry.markStatus(FEATURE_STORAGE, "failed",
                        "MediaStore shared M4A finalizer", "trigger=" + trigger,
                        "Recording import broadcast failed");
                sendRecordingNotification(application,
                        CallRecordingNotificationReceiver.STATE_FAILED);
            }
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_STORAGE,
                    "CallRecordingImportReceiver", throwable);
            sendRecordingNotification(application,
                    CallRecordingNotificationReceiver.STATE_FAILED);
        }
    }

    private static boolean awaitNativeWav(File file) {
        long deadline = System.currentTimeMillis() + WAV_READY_TIMEOUT_MS;
        long previousLength = -1L;
        int stablePasses = 0;
        while (System.currentTimeMillis() <= deadline) {
            long length = file == null || !file.isFile() ? -1L : file.length();
            if (length > 0L && CallRecordingStore.isNativeImportReady(file)) {
                if (length == previousLength) {
                    stablePasses++;
                    if (stablePasses >= 2) {
                        return true;
                    }
                } else {
                    stablePasses = 0;
                }
            } else {
                stablePasses = 0;
            }
            previousLength = length;
            try {
                Thread.sleep(WAV_READY_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        // The native ZRTC writer never back-patched the RIFF/data length fields,
        // so the stream never validates as a finished WAV. The file size has
        // stopped changing by now; rebuild the header from it and re-check.
        return CallRecordingStore.repairNativeImport(file);
    }

    private static boolean enqueueImport(
            Context application,
            File sourceFile,
            String pendingName,
            String displayName,
            String phoneNumber,
            String trigger) {
        SelfCheckRegistry.markStatus(FEATURE_TRANSFER, "installed_no_hits",
                "CallRecordingImportReceiver acknowledgment", "trigger=" + trigger, "");
        AtomicBoolean acknowledged = new AtomicBoolean();
        IBinder source = CallRecordingImportProtocol.source(sourceFile, accepted -> {
            synchronized (acknowledged) {
                acknowledged.set(true);
                if (accepted) {
                    SelfCheckRegistry.markInstalled(FEATURE_TRANSFER,
                            "CallRecordingImportReceiver private WAV copy", 1);
                    SelfCheckRegistry.incrementHit(FEATURE_TRANSFER,
                            "CallRecordingImportReceiver private WAV copy", "trigger=" + trigger);
                    sourceFile.delete();
                } else {
                    SelfCheckRegistry.markStatus(FEATURE_TRANSFER, "failed",
                            "CallRecordingImportReceiver", "trigger=" + trigger,
                            "Private WAV copy rejected; source preserved");
                    sendRecordingNotification(application,
                            CallRecordingNotificationReceiver.STATE_FAILED);
                }
            }
        });
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE,
                CallRecordingImportReceiver.class.getName()));
        Bundle extras = new Bundle();
        extras.putBinder(CallRecordingImportReceiver.EXTRA_SOURCE_BINDER, source);
        extras.putString(CallRecordingImportReceiver.EXTRA_PENDING_NAME, pendingName);
        extras.putString(CallRecordingImportReceiver.EXTRA_DISPLAY_NAME, displayName);
        extras.putString(CallRecordingImportReceiver.EXTRA_PHONE_NUMBER, phoneNumber);
        intent.putExtras(extras);
        try {
            STOP_SCHEDULER.schedule(() -> {
                synchronized (acknowledged) {
                    if (!acknowledged.get()) {
                        // A late positive acknowledgment still permits cleanup. A timeout never does.
                        SelfCheckRegistry.markStatus(FEATURE_TRANSFER, "failed",
                                "CallRecordingImportReceiver acknowledgment", "trigger=" + trigger,
                                "Private WAV copy not acknowledged within 30 seconds; source preserved");
                    }
                }
            }, IMPORT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            application.sendBroadcast(intent);
            return true;
        } catch (Throwable throwable) {
            acknowledged.set(true);
            SelfCheckRegistry.markFailed(FEATURE_TRANSFER,
                    "CallRecordingImportReceiver#send", throwable);
            return false;
        }
    }

    private static void sendRecordingNotification(Context application, String state) {
        if (!HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS)) {
            return;
        }
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MODULE_PACKAGE,
                CallRecordingNotificationReceiver.class.getName()));
        Bundle extras = new Bundle();
        extras.putBinder(CallRecordingNotificationReceiver.EXTRA_IDENTITY_BINDER,
                CallRecordingImportProtocol.identity());
        extras.putString(CallRecordingNotificationReceiver.EXTRA_STATE, state);
        intent.putExtras(extras);
        try {
            application.sendBroadcast(intent);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_NOTIFICATIONS,
                    "CallRecordingNotificationReceiver#send", throwable);
        }
    }

    private static void recoverPendingRecordings() {
        Context application = HookConfig.resolveFallbackContextForHooks();
        if (application == null) {
            return;
        }
        File directory = new File(application.getCacheDir(), TEMP_DIRECTORY);
        File[] pending = directory.listFiles((ignored, name) -> name.endsWith(".part"));
        if (pending == null) {
            return;
        }
        for (File file : pending) {
            if (CallRecordingStore.isNativeImportReady(file)
                    || CallRecordingStore.repairNativeImport(file)) {
                enqueueImport(application, file, file.getName(),
                        "Zalo contact", "", "startup_recovery");
            } else {
                // Keep the incomplete source for recovery, but do not make one historical
                // zero-byte native file report the currently installed recorder hook as failed
                // on every Zalo startup.
                SelfCheckRegistry.markStatus(FEATURE_STORAGE, "installed_no_hits",
                        "ZRTC native WAV recovery", "size=" + file.length(),
                        "Preserved native WAV is still incomplete");
            }
        }
    }

    private static void scheduleActivityFallback(int attempt) {
        STOP_SCHEDULER.schedule(() -> {
            ArrayList<Session> sessions;
            synchronized (SESSIONS) {
                sessions = new ArrayList<>(SESSIONS.values());
            }
            boolean active = false;
            for (Session session : sessions) {
                if (isPeerActive(session)) {
                    active = true;
                } else {
                    stop(session, "activity_destroy_inactive");
                }
            }
            if (active && attempt < ACTIVITY_FALLBACK_ATTEMPTS) {
                scheduleActivityFallback(attempt + 1);
            }
        }, ACTIVITY_FALLBACK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static boolean isPeerActive(Session session) {
        if (session.deleted) {
            return false;
        }
        Method method = isInCallMethod;
        if (method == null) {
            return true;
        }
        try {
            Object value = method.invoke(null, session.peerHandle);
            return !(value instanceof Boolean) || (Boolean) value;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_HOOKS,
                    "PeerJNI#zrtc_peer_is_in_call", unwrap(throwable));
            return true;
        }
    }

    private static void stopForPeer(long peerHandle, String trigger) {
        Session session = SESSIONS_BY_PEER.get(peerHandle);
        if (session != null) {
            synchronized (session) {
                if (trigger.endsWith("zrtc_peer_delete")) {
                    session.deleted = true;
                }
            }
            // stop() may synchronously dispatch to the main thread. Do not hold the session
            // monitor while waiting, or the main thread cannot enter stopOnMain().
            stop(session, trigger);
            synchronized (session) {
                if (session.deleted) SESSIONS_BY_PEER.remove(peerHandle, session);
            }
        }
    }

    /** Records one ordered call-path entry; a new call starts a fresh trace after a quiet gap. */
    private static void recordEvent(String text) {
        long now = System.currentTimeMillis();
        if (now - LAST_EVENT_AT.getAndSet(now) > EVENT_TRACE_GAP_MS) {
            CALL_EVENTS.clear();
            callEpoch = now;
        }
        long epoch = callEpoch;
        CALL_EVENTS.addLast(epoch == 0L ? text : "+" + (now - epoch) + "ms " + text);
        while (CALL_EVENTS.size() > EVENT_TRACE_LIMIT) {
            CALL_EVENTS.pollFirst();
        }
    }

    private static String eventTrace() {
        return String.join(",", CALL_EVENTS);
    }

    /** Short peer-handle tag for the self-check row. */
    private static String peerTag(long peerHandle) {
        return "0x" + Long.toHexString(peerHandle);
    }

    private static int firstInt(Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Integer) {
                    return (Integer) arg;
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isCallCallback(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (CALL_CALLBACK.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void markStale(String reason) {
        SelfCheckRegistry.markStale(FEATURE_HOOKS, "ZRTC one-to-one call audio recorder", reason);
        SelfCheckRegistry.markStale(FEATURE_NATIVE, "ZRTC native WAV recorder", reason);
        SelfCheckRegistry.markStale(FEATURE_STORAGE, "MediaStore shared M4A", reason);
        SelfCheckRegistry.markStale(FEATURE_NOTIFICATIONS, "Recording status notifications", reason);
        SelfCheckRegistry.markStale(FEATURE_VIDEO_STATE, "ZRTC video state", reason);
        SelfCheckRegistry.markStale(FEATURE_METADATA, "Zalo contact phone lookup", reason);
    }

    private static Throwable unwrap(Throwable throwable) {
        return throwable.getCause() == null ? throwable : throwable.getCause();
    }

    private static final class Session {
        final long peerHandle;
        final String peerUid;
        /** Creation order lets a new call's binding displace a reused callback's old session. */
        final long sequence = SESSION_SEQUENCE.incrementAndGet();
        String direction = "unknown";
        long startedAt;
        String pendingName;
        File tempFile;
        boolean confirmed;
        boolean audioConnected;
        boolean started;
        int startAttempts;
        volatile boolean deleted;

        Session(long peerHandle, String peerUid) {
            this.peerHandle = peerHandle;
            this.peerUid = peerUid;
        }
    }
}
