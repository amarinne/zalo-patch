package com.ez.zalopatch.xposed.features;

import android.app.Activity;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Metadata-only prerequisite probe for call recording.
 *
 * This feature observes Zalo's existing call lifecycle and audio callbacks. It never registers an
 * audio stream, reads sample values, writes files, or invokes Zalo's native recording functions.
 */
public final class CallRecordingProbeFeature extends Feature {
    private static final String FEATURE_LIFECYCLE = "calls.recording_probe.lifecycle";
    private static final String FEATURE_STREAM_REGISTRATION = "calls.recording_probe.stream_registration";
    private static final String FEATURE_AUDIO = "calls.recording_probe.audio";
    private static final String CALL_CALLBACK = "com.vng.zing.vn.zrtc.CallCallback";
    private static final String GROUP_CALLBACK = "com.vng.zing.vn.zrtc.GroupCallback";
    private static final String PEER_JNI = "com.vng.zing.vn.zrtc.PeerJNI";
    private static final String GROUP_PEER_JNI = "com.vng.zing.vn.zrtc.GroupCallPeerJNI";
    private static final String[] CALL_ACTIVITIES = new String[]{
            "zm.voip.ui.incall.ZmInCallActivity",
            "zm.voip.ui.incall.GroupCallActivity"
    };
    private static final Set<String> LIFECYCLE_METHODS = nameSet(
            "onCallState", "onCallAudioState", "onCallVideoState", "onIncomingCall", "onMakeCall",
            "onCallConfirmed", "onCallEnd", "onMeetingEnded", "onCallInit", "onCallAutoHangup",
            "onCallErr", "onCallJoinMeetingSuccess", "onCallJoinMeetingFailed");
    private static final Set<String> AUDIO_METHODS = nameSet("onInAudioStream", "onOutAudioStream");
    private static final Set<String> HOOKED_METHODS = Collections.synchronizedSet(new HashSet<String>());
    private static final AtomicLong IN_AUDIO_COUNT = new AtomicLong();
    private static final AtomicLong OUT_AUDIO_COUNT = new AtomicLong();

    public CallRecordingProbeFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "CallRecordingProbe";
    }

    @Override
    public void doHook() {
        if (!HookConfig.isEnabled(Tweaks.KEY_CALL_RECORDING_PROBE)) {
            SelfCheckRegistry.markDisabled(FEATURE_LIFECYCLE, "Zalo call callbacks");
            SelfCheckRegistry.markDisabled(FEATURE_STREAM_REGISTRATION, "Zalo audio stream registration");
            SelfCheckRegistry.markDisabled(FEATURE_AUDIO, "Zalo audio callback shape");
            return;
        }

        int lifecycleHooks = hookActivities();
        lifecycleHooks += hookCallbackBase(CALL_CALLBACK);
        lifecycleHooks += hookCallbackBase(GROUP_CALLBACK);
        int peerRegistrationHooks = hookCallbackRegistration(
                PEER_JNI, "zrtc_peer_register_callback");
        int groupRegistrationHooks = hookCallbackRegistration(
                GROUP_PEER_JNI, "group_call_peer_register_callback");
        lifecycleHooks += peerRegistrationHooks + groupRegistrationHooks;
        int streamRegistrationHooks = hookStreamRegistration();

        if (lifecycleHooks > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_LIFECYCLE,
                    "ZRTC callbacks and call activities", lifecycleHooks);
        } else {
            SelfCheckRegistry.markStale(FEATURE_LIFECYCLE,
                    "ZRTC callbacks and call activities", "no stable call anchors found");
        }

        if (streamRegistrationHooks > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_STREAM_REGISTRATION,
                    "PeerJNI in/out stream registration", streamRegistrationHooks);
        } else {
            SelfCheckRegistry.markStale(FEATURE_STREAM_REGISTRATION,
                    "PeerJNI in/out stream registration", "registration methods missing");
        }

        int audioHooks = audioHookCount();
        if (audioHooks > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_AUDIO,
                    "CallCallback in/out stream metadata", audioHooks);
        } else {
            SelfCheckRegistry.markStale(FEATURE_AUDIO,
                    "CallCallback in/out stream metadata", "no callback hooks installed");
        }
    }

    private int hookActivities() {
        int count = 0;
        for (String className : CALL_ACTIVITIES) {
            Class<?> activityClass = XposedHelpers.findClassIfExists(className, classLoader);
            if (activityClass == null || !Activity.class.isAssignableFrom(activityClass)) {
                continue;
            }
            count += hookActivityMethod(activityClass, "onCreate");
            count += hookActivityMethod(activityClass, "onDestroy");
        }
        return count;
    }

    private int hookActivityMethod(final Class<?> activityClass, final String methodName) {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(activityClass, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        SelfCheckRegistry.incrementHit(FEATURE_LIFECYCLE,
                                activityClass.getName() + "#" + methodName,
                                "event=" + methodName);
                    }
                });
        return hooks.size();
    }

    private int hookCallbackRegistration(final String peerClassName, final String methodName) {
        Class<?> peerClass = XposedHelpers.findClassIfExists(peerClassName, classLoader);
        if (peerClass == null) {
            return 0;
        }
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(peerClass,
                methodName, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) {
                            return;
                        }
                        for (Object arg : param.args) {
                            if (arg != null && isCallbackClass(arg.getClass())) {
                                hookCallbackClass(arg.getClass());
                                SelfCheckRegistry.markInstalled(FEATURE_LIFECYCLE,
                                        arg.getClass().getName() + " call callbacks", 1);
                                int audioHooks = audioHookCount();
                                if (audioHooks > 0) {
                                    SelfCheckRegistry.markInstalled(FEATURE_AUDIO,
                                            "CallCallback in/out stream metadata", audioHooks);
                                }
                                return;
                            }
                        }
                    }
                });
        return hooks.size();
    }

    private int hookStreamRegistration() {
        Class<?> peerClass = XposedHelpers.findClassIfExists(PEER_JNI, classLoader);
        if (peerClass == null) {
            return 0;
        }
        int count = 0;
        count += hookStreamRegistrationMethod(peerClass, "zrtc_peer_register_in_audio_stream");
        count += hookStreamRegistrationMethod(peerClass, "zrtc_peer_register_out_audio_stream");
        return count;
    }

    private int hookStreamRegistrationMethod(final Class<?> peerClass, final String methodName) {
        Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(peerClass, methodName,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        SelfCheckRegistry.incrementHit(FEATURE_STREAM_REGISTRATION,
                                peerClass.getName() + "#" + methodName,
                                streamRegistrationDetail(methodName, param.args));
                    }
                });
        return hooks.size();
    }

    private int hookCallbackBase(String className) {
        Class<?> callbackClass = XposedHelpers.findClassIfExists(className, classLoader);
        return callbackClass == null ? 0 : hookCallbackClass(callbackClass);
    }

    private int hookCallbackClass(Class<?> callbackClass) {
        int count = 0;
        for (Class<?> current = callbackClass; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (Modifier.isAbstract(method.getModifiers())) {
                    continue;
                }
                String methodName = method.getName();
                if (!LIFECYCLE_METHODS.contains(methodName) && !AUDIO_METHODS.contains(methodName)) {
                    continue;
                }
                String signature = current.getName() + "#" + method.toGenericString();
                if (!HOOKED_METHODS.add(signature)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callbackHook(method));
                    count++;
                } catch (Throwable throwable) {
                    HOOKED_METHODS.remove(signature);
                    SelfCheckRegistry.markFailed(
                            AUDIO_METHODS.contains(methodName) ? FEATURE_AUDIO : FEATURE_LIFECYCLE,
                            current.getName() + "#" + methodName, throwable);
                }
            }
        }
        return count;
    }

    private XC_MethodHook callbackHook(final Method method) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String methodName = method.getName();
                String target = method.getDeclaringClass().getName() + "#" + methodName;
                if (AUDIO_METHODS.contains(methodName)) {
                    reportAudioShape(methodName, target, param.args);
                    return;
                }
                SelfCheckRegistry.incrementHit(FEATURE_LIFECYCLE, target,
                        lifecycleDetail(methodName, param.args));
            }
        };
    }

    private static void reportAudioShape(String methodName, String target, Object[] args) {
        AtomicLong counter = "onInAudioStream".equals(methodName) ? IN_AUDIO_COUNT : OUT_AUDIO_COUNT;
        long callbackCount = counter.incrementAndGet();
        if (callbackCount != 1L && callbackCount != 100L && callbackCount % 500L != 0L) {
            return;
        }
        int arrayLength = -1;
        int sampleCount = -1;
        long timestamp = -1L;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof short[]) {
                    arrayLength = ((short[]) arg).length;
                } else if (arg instanceof Integer) {
                    sampleCount = (Integer) arg;
                } else if (arg instanceof Long) {
                    timestamp = (Long) arg;
                }
            }
        }
        SelfCheckRegistry.incrementHit(FEATURE_AUDIO, target,
                "stream=" + methodName + " array_length=" + arrayLength
                        + " sample_count=" + sampleCount + " timestamp=" + timestamp
                        + " callback_count=" + callbackCount);
    }

    private static String streamRegistrationDetail(String methodName, Object[] args) {
        StringBuilder detail = new StringBuilder("event=").append(methodName);
        if (args == null) {
            return detail.toString();
        }
        int intIndex = 0;
        for (Object arg : args) {
            if (arg instanceof Integer) {
                detail.append(" int").append(intIndex++).append('=').append(arg);
            } else if (arg instanceof Boolean) {
                detail.append(" enabled=").append(arg);
            }
        }
        return detail.toString();
    }

    private static String lifecycleDetail(String methodName, Object[] args) {
        if (args != null && args.length > 0 && args[0] instanceof Number) {
            return "event=" + methodName + " state=" + args[0];
        }
        return "event=" + methodName;
    }

    private static boolean isCallbackClass(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (CALL_CALLBACK.equals(name) || GROUP_CALLBACK.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static int audioHookCount() {
        int count = 0;
        synchronized (HOOKED_METHODS) {
            for (String signature : HOOKED_METHODS) {
                if (signature.contains("onInAudioStream") || signature.contains("onOutAudioStream")) {
                    count++;
                }
            }
        }
        return count;
    }

    private static Set<String> nameSet(String... values) {
        Set<String> names = new HashSet<>();
        Collections.addAll(names, values);
        return Collections.unmodifiableSet(names);
    }
}
