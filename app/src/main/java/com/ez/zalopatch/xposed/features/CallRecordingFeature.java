package com.ez.zalopatch.xposed.features;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import com.ez.zalopatch.CallRecordingImportProtocol;
import com.ez.zalopatch.CallRecordingImportReceiver;
import com.ez.zalopatch.CallRecordingNotificationReceiver;
import com.ez.zalopatch.CallRecordingStore;
import com.ez.zalopatch.CallRecordingMetadata;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.ZaloContactResolver;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Default-off native ZRTC audio recorder for one-to-one voice and video calls. */
public final class CallRecordingFeature extends Feature {
    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static final String TEMP_DIRECTORY = "zalo_patch_call_recordings";
    private static final String FEATURE_HOOKS = "calls.auto_record.hooks";
    private static final String FEATURE_NATIVE = "calls.auto_record.native";
    private static final String FEATURE_STORAGE = "calls.auto_record.storage";
    private static final String FEATURE_NOTIFICATIONS = "calls.auto_record.notifications";
    private static final String FEATURE_VIDEO_STATE = "calls.auto_record.video_state";
    private static final String FEATURE_METADATA = "calls.auto_record.metadata";
    private static final String CALL_CALLBACK = "com.vng.zing.vn.zrtc.CallCallback";
    private static final String PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI";
    private static final long CALLBACK_STOP_DELAY_MS = 250L;
    private static final long ACTIVITY_FALLBACK_DELAY_MS = 500L;
    private static final int ACTIVITY_FALLBACK_ATTEMPTS = 5;
    private static final long WAV_READY_TIMEOUT_MS = 5_000L;
    private static final long WAV_READY_POLL_MS = 100L;
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
    private static volatile Method recordMethod;
    private static volatile Method isInCallMethod;

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
            SelfCheckRegistry.markDisabled(FEATURE_STORAGE, "MediaStore shared M4A");
            SelfCheckRegistry.markDisabled(FEATURE_NOTIFICATIONS, "Recording status notifications");
            SelfCheckRegistry.markDisabled(FEATURE_VIDEO_STATE, "ZRTC video state");
            SelfCheckRegistry.markDisabled(FEATURE_METADATA, "Zalo contact phone lookup");
            return;
        }

        Class<?> peerClass = XposedHelpers.findClassIfExists(PEER_JNI, classLoader);
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
        hooks += hookPeerTermination(peerClass);
        hooks += hookCallbackRegistration(peerClass);
        hooks += hookCallbackBase();
        hooks += hookActivities();
        if (hooks == 0) {
            markStale("No call lifecycle hooks installed");
            return;
        }
        SelfCheckRegistry.markInstalled(FEATURE_HOOKS,
                "PeerJNI callback and ZmInCallActivity audio lifecycle", hooks);
        SelfCheckRegistry.markInstalled(FEATURE_NATIVE,
                "PeerJNI#zrtc_peer_start_record_audio", 1);
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
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(peerClass,
                "zrtc_peer_register_callback", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
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
                        Session session = new Session(peerHandle, PEER_PARTNERS.get(peerHandle));
                        SESSIONS.put(callback, session);
                        SESSIONS_BY_PEER.put(peerHandle, session);
                    }
                });
        return hooks.size();
    }

    private int hookPeerTermination(Class<?> peerClass) {
        int count = 0;
        for (String methodName : new String[]{
                "zrtc_peer_end_call", "zrtc_peer_force_stop", "zrtc_peer_delete"
        }) {
            count += XposedBridge.hookAllMethods(peerClass, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
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
        count += XposedBridge.hookAllMethods(peerClass,
                "zrtc_call_config_set_partner_id", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
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
        XC_MethodHook bindPeer = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 2
                        || !(param.args[0] instanceof Long) || !(param.args[1] instanceof Long)) {
                    return;
                }
                String partner = CONFIG_PARTNERS.get((Long) param.args[1]);
                if (partner != null && !partner.isEmpty()) {
                    PEER_PARTNERS.put((Long) param.args[0], partner);
                }
            }
        };
        count += XposedBridge.hookAllMethods(peerClass, "zrtc_peer_make_call", bindPeer).size();
        count += XposedBridge.hookAllMethods(peerClass, "zrtc_peer_incoming_call", bindPeer).size();
        return count;
    }

    private int hookCallbackBase() {
        Class<?> callbackClass = XposedHelpers.findClassIfExists(CALL_CALLBACK, classLoader);
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
                    XposedBridge.hookMethod(method, callbackHook(method));
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

    private XC_MethodHook callbackHook(final Method method) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Session session = SESSIONS.get(param.thisObject);
                if (session == null) {
                    return;
                }
                String methodName = method.getName();
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
                    scheduleStop(session, methodName);
                    return;
                }
                if (CallRecordingLifecycle.shouldStartAudio(methodName, state)) {
                    start(session, methodName);
                }
            }
        };
    }

    private int hookActivities() {
        int count = 0;
        for (String className : CALL_ACTIVITIES) {
            Class<?> activityClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (activityClass == null || !Activity.class.isAssignableFrom(activityClass)) {
                continue;
            }
            count += XposedBridge.hookAllMethods(activityClass, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    scheduleActivityFallback(1);
                }
            }).size();
        }
        return count;
    }

    private static void start(Session session, String trigger) {
        synchronized (session) {
            if (session.started) {
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
            session.startedAt = System.currentTimeMillis();
            session.pendingName = CallRecordingStore.newPendingName(
                    session.startedAt, session.direction);
            try {
                File directory = new File(application.getCacheDir(), TEMP_DIRECTORY);
                if (!directory.exists() && !directory.mkdirs()) {
                    throw new IllegalStateException("Could not create temporary recording directory");
                }
                session.tempFile = new File(directory, session.pendingName);
                nativeRecord.invoke(null, session.peerHandle, true,
                        session.tempFile.getAbsolutePath());
                session.started = true;
                if (HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS)) {
                    sendRecordingNotification(application,
                            CallRecordingNotificationReceiver.STATE_RUNNING);
                }
                SelfCheckRegistry.incrementHit(FEATURE_NATIVE,
                        "PeerJNI#zrtc_peer_start_record_audio",
                        "start direction=" + session.direction + " trigger=" + trigger
                                + " media=audio_only");
            } catch (Throwable throwable) {
                if (session.tempFile != null) {
                    session.tempFile.delete();
                }
                SelfCheckRegistry.markFailed(FEATURE_NATIVE,
                        "PeerJNI#zrtc_peer_start_record_audio", unwrap(throwable));
            }
        }
    }

    private static void stop(Session session, String trigger) {
        Context application;
        File tempFile;
        String pendingName;
        String effectivePeerUid;
        CallRecordingMetadata.Snapshot observed;
        synchronized (session) {
            if (!session.started) {
                return;
            }
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
        }
        final Context finalApplication = application;
        final File finalTempFile = tempFile;
        final String finalPendingName = pendingName;
        final String finalPeerUid = effectivePeerUid;
        final CallRecordingMetadata.Snapshot finalObserved = observed;
        FINALIZER.execute(() -> finalizeRecording(finalApplication, finalTempFile,
                finalPendingName, finalPeerUid, finalObserved, trigger));
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
                    "ZRTC native WAV readiness", "trigger=" + trigger + " size=" + size,
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
        return false;
    }

    private static boolean enqueueImport(
            Context application,
            File sourceFile,
            String pendingName,
            String displayName,
            String phoneNumber,
            String trigger) {
        IBinder source = CallRecordingImportProtocol.source(sourceFile, accepted -> {
            if (accepted) {
                sourceFile.delete();
            } else {
                SelfCheckRegistry.markStatus(FEATURE_STORAGE, "failed",
                        "CallRecordingImportReceiver", "trigger=" + trigger,
                        "Native WAV could not be queued for conversion");
                sendRecordingNotification(application,
                        CallRecordingNotificationReceiver.STATE_FAILED);
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
            application.sendBroadcast(intent);
            return true;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_STORAGE,
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
            if (CallRecordingStore.isNativeImportReady(file)) {
                enqueueImport(application, file, file.getName(),
                        "Zalo contact", "", "startup_recovery");
            } else {
                SelfCheckRegistry.markStatus(FEATURE_STORAGE, "failed",
                        "ZRTC native WAV recovery", "size=" + file.length(),
                        "Preserved native WAV is still incomplete");
            }
        }
    }

    private static void scheduleStop(Session session, String trigger) {
        synchronized (session) {
            if (!session.started || session.stopScheduled) {
                return;
            }
            session.stopScheduled = true;
        }
        STOP_SCHEDULER.schedule(() -> stop(session, trigger),
                CALLBACK_STOP_DELAY_MS, TimeUnit.MILLISECONDS);
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
            stop(session, trigger);
            if (trigger.endsWith("zrtc_peer_delete")) {
                session.deleted = true;
                SESSIONS_BY_PEER.remove(peerHandle, session);
            }
        }
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
        String direction = "unknown";
        long startedAt;
        String pendingName;
        File tempFile;
        boolean started;
        boolean stopScheduled;
        volatile boolean deleted;

        Session(long peerHandle, String peerUid) {
            this.peerHandle = peerHandle;
            this.peerUid = peerUid;
        }
    }
}
