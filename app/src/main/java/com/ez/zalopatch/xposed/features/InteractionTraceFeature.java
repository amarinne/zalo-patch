package com.ez.zalopatch.xposed.features;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import com.ez.zalopatch.xposed.core.Feature;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class InteractionTraceFeature extends Feature {
    private static final String TAG = "ZaloPatch";
    private static final String PROPERTY = "debug.zalopatch.trace";
    private static final long DEDUP_WINDOW_MS = 1000L;
    private static final Map<String, Long> RECENT_MESSAGES = new ConcurrentHashMap<>();

    public InteractionTraceFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "InteractionTrace";
    }

    @Override
    public void doHook() {
        if (!enabled()) {
            return;
        }
        hookDispatchTouch();
        hookClickMethods();
        hookAddView();
        hookWindowManagerAddView();
        trace("enabled property=" + PROPERTY);
    }

    private void hookDispatchTouch() {
        XposedBridge.hookAllMethods(View.class, "dispatchTouchEvent", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof View) || param.args.length == 0
                        || !(param.args[0] instanceof MotionEvent)) {
                    return;
                }
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();
                if (action != MotionEvent.ACTION_DOWN
                        && action != MotionEvent.ACTION_UP
                        && action != MotionEvent.ACTION_CANCEL) {
                    return;
                }
                View view = (View) param.thisObject;
                trace("touch " + actionName(action)
                        + " raw=" + (int) event.getRawX() + "," + (int) event.getRawY()
                        + " view=" + describe(view));
            }
        });
    }

    private void hookClickMethods() {
        XposedBridge.hookAllMethods(View.class, "performClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    trace("performClick " + describe((View) param.thisObject));
                }
            }
        });
        XposedBridge.hookAllMethods(View.class, "performLongClick", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof View) {
                    trace("performLongClick " + describe((View) param.thisObject));
                }
            }
        });
    }

    private void hookAddView() {
        XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof ViewGroup) || param.args.length == 0
                        || !(param.args[0] instanceof View)) {
                    return;
                }
                trace("ViewGroup.addView parent=" + describe((ViewGroup) param.thisObject)
                        + " child=" + describe((View) param.args[0]));
            }
        });
    }

    private void hookWindowManagerAddView() {
        Class<?> impl = XposedHelpers.findClassIfExists("android.view.WindowManagerImpl", classLoader);
        if (impl == null) {
            trace("WindowManagerImpl unavailable");
            return;
        }
        for (Method method : impl.getDeclaredMethods()) {
            if (!"addView".equals(method.getName()) || method.getParameterTypes().length < 1
                    || !View.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            trace("WindowManager.addView " + describe((View) param.args[0]));
                        }
                    }
                });
            } catch (Throwable throwable) {
                trace("WindowManager.addView hook failed " + throwable.getClass().getSimpleName());
            }
        }
    }

    private static String describe(View view) {
        int[] loc = new int[]{0, 0};
        try {
            view.getLocationOnScreen(loc);
        } catch (Throwable ignored) {
        }
        return view.getClass().getName()
                + " bounds=" + loc[0] + "," + loc[1] + "+"
                + view.getWidth() + "x" + view.getHeight()
                + " visible=" + view.getVisibility()
                + " clickable=" + view.isClickable()
                + " longClickable=" + view.isLongClickable()
                + " ancestry=" + ancestry(view);
    }

    private static String ancestry(View view) {
        StringBuilder builder = new StringBuilder();
        View current = view;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (builder.length() > 0) {
                builder.append("<-");
            }
            builder.append(current.getClass().getName());
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return compact(builder.toString());
    }

    private static String actionName(int action) {
        if (action == MotionEvent.ACTION_DOWN) {
            return "DOWN";
        }
        if (action == MotionEvent.ACTION_UP) {
            return "UP";
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            return "CANCEL";
        }
        return String.valueOf(action);
    }

    private static boolean enabled() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            String value = (String) systemProperties.getMethod("get", String.class, String.class)
                    .invoke(null, PROPERTY, "0");
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String compact(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 260 ? value.substring(0, 260) : value;
    }

    private static void trace(String message) {
        String compact = compact(message);
        long now = System.currentTimeMillis();
        Long last = RECENT_MESSAGES.get(compact);
        if (last != null && now - last < DEDUP_WINDOW_MS) {
            return;
        }
        RECENT_MESSAGES.put(compact, now);
        Log.i(TAG, "ZaloPatch: [InteractionTrace] " + compact);
    }
}
