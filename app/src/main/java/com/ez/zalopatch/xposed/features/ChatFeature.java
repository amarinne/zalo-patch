package com.ez.zalopatch.xposed.features;

import android.app.Dialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class ChatFeature extends Feature {
    private static final String FEATURE_REACTION_ROW = "messages.reaction_row";
    private static final String CHAT_ROW_CLASS = "com.zing.zalo.ui.chat.chatrow.ChatRow";
    private static final String REACTION_PICKER_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerInContextMenuView";
    private static final String REACTION_SCROLL_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionScrollView";
    private static final String REACTION_PICKER_BASE_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerView";

    private final Set<Integer> hiddenReactionRows = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> disabledReactionGestures = Collections.synchronizedSet(new HashSet<>());

    public ChatFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "Chat";
    }

    @Override
    public void doHook() {
        installReactionRowRemoval();
    }

    private void installReactionRowRemoval() {
        if (!HookConfig.isEnabled(Tweaks.KEY_HIDE_REACTION_ROW)) {
            SelfCheckRegistry.markDisabled(FEATURE_REACTION_ROW, "chat popup surfaces");
            return;
        }
        int hooked = hookPopupSurfaceScans();
        hooked += hookReactionClasses();
        hooked += hookReactionGestureSuppression();
        hooked += hookReactionHeartLongPress();
        hooked += hookReactionAddView();
        if (hooked > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_REACTION_ROW, "chat popup surface scan", hooked);
            return;
        }
        List<String> popupAdapters = SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.reaction_popup_adapter_classes");
        List<String> itemClasses = SymbolSchema.strings(HookConfig.resolveModuleContextForHooks(),
                "symbols.chat.reaction_item_classes");
        if (popupAdapters.isEmpty() || itemClasses.isEmpty()) {
            SelfCheckRegistry.markStale(FEATURE_REACTION_ROW, "symbols.chat",
                    "reaction popup symbols missing; run chat rendering trace first");
            return;
        }
        SelfCheckRegistry.markStale(FEATURE_REACTION_ROW, "symbols.chat",
                "schema present but popup mutation not enabled until trace confirms reaction row boundary");
    }

    private int hookPopupSurfaceScans() {
        int hooked = 0;
        try {
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", popupHook());
            hooked++;
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", popupHook());
            hooked++;
            XposedBridge.hookAllMethods(Dialog.class, "show", dialogHook());
            hooked++;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, "PopupWindow/Dialog", throwable);
        }
        hooked += hookWindowManagerAddView();
        return hooked;
    }

    private int hookReactionClasses() {
        int hooked = 0;
        hooked += hookReactionClass(REACTION_PICKER_CLASS);
        hooked += hookReactionClass(REACTION_SCROLL_CLASS);
        return hooked;
    }

    private int hookReactionGestureSuppression() {
        Class<?> clazz = XposedHelpers.findClassIfExists(REACTION_PICKER_BASE_CLASS, classLoader);
        if (clazz == null) {
            return 0;
        }
        int hooked = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (!boolean.class.equals(method.getReturnType()) || params.length != 1
                    || !MotionEvent.class.equals(params[0])) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null
                                && REACTION_PICKER_CLASS.equals(param.thisObject.getClass().getName())) {
                            param.setResult(true);
                            int identity = System.identityHashCode(param.thisObject);
                            if (disabledReactionGestures.add(identity)) {
                                SelfCheckRegistry.markSuppressed(FEATURE_REACTION_ROW,
                                        REACTION_PICKER_CLASS + "#" + method.getName(),
                                        "blocked hidden reaction gesture");
                            }
                        }
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW,
                        REACTION_PICKER_BASE_CLASS + "#" + method.getName(), throwable);
            }
        }
        return hooked;
    }

    private int hookReactionHeartLongPress() {
        Context context = HookConfig.resolveModuleContextForHooks();
        String methodName = SymbolSchema.string(context,
                "symbols.chat.reaction_long_press_method", "");
        String armedField = SymbolSchema.string(context,
                "symbols.chat.reaction_long_press_armed_field", "");
        if (methodName.isEmpty() || armedField.isEmpty()) {
            return 0;
        }
        Class<?> rowClass = XposedHelpers.findClassIfExists(CHAT_ROW_CLASS, classLoader);
        if (rowClass == null) {
            return 0;
        }
        int hooked = 0;
        for (Method method : rowClass.getDeclaredMethods()) {
            if (!methodName.equals(method.getName()) || method.getParameterTypes().length != 0
                    || !void.class.equals(method.getReturnType())) {
                continue;
            }
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!XposedHelpers.getBooleanField(param.thisObject, armedField)) {
                            return;
                        }
                        XposedHelpers.setBooleanField(param.thisObject, armedField, false);
                        param.setResult(null);
                        SelfCheckRegistry.markSuppressed(FEATURE_REACTION_ROW,
                                CHAT_ROW_CLASS + "#" + methodName,
                                "blocked reaction-heart long press");
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW,
                        CHAT_ROW_CLASS + "#" + methodName, throwable);
            }
        }
        return hooked;
    }

    private int hookReactionClass(String className) {
        Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
        if (clazz == null) {
            return 0;
        }
        try {
            XposedBridge.hookAllConstructors(clazz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof View) {
                        removeReactionView((View) param.thisObject, "constructor");
                    }
                }
            });
            return 1;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, className, throwable);
            return 0;
        }
    }

    private int hookReactionAddView() {
        try {
            XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    removeReactionArgs(param);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    removeReactionArgs(param);
                }
            });
            return 1;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, "ViewGroup.addView reaction", throwable);
            return 0;
        }
    }

    private void removeReactionArgs(XC_MethodHook.MethodHookParam param) {
        if (param.thisObject instanceof View && isReactionContainer((View) param.thisObject)) {
            removeReactionView((View) param.thisObject, "ViewGroup.addView parent");
        }
        if (param.args == null) {
            return;
        }
        for (Object arg : param.args) {
            if (arg instanceof View && isReactionContainer((View) arg)) {
                removeReactionView((View) arg, "ViewGroup.addView child");
            }
        }
    }

    private XC_MethodHook popupHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof PopupWindow) {
                    scheduleScan(((PopupWindow) param.thisObject).getContentView(), "PopupWindow");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof PopupWindow) {
                    scheduleScan(((PopupWindow) param.thisObject).getContentView(), "PopupWindow");
                }
            }
        };
    }

    private XC_MethodHook dialogHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof Dialog)) {
                    return;
                }
                Dialog dialog = (Dialog) param.thisObject;
                if (dialog.getWindow() != null) {
                    scheduleScan(dialog.getWindow().getDecorView(), "Dialog");
                }
            }
        };
    }

    private int hookWindowManagerAddView() {
        Class<?> impl = XposedHelpers.findClassIfExists("android.view.WindowManagerImpl", classLoader);
        if (impl == null) {
            return 0;
        }
        int hooked = 0;
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
                            scheduleScan((View) param.args[0], "WindowManager.addView");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            scheduleScan((View) param.args[0], "WindowManager.addView");
                        }
                    }
                });
                hooked++;
            } catch (Throwable throwable) {
                SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, "WindowManagerImpl#addView", throwable);
            }
        }
        return hooked;
    }

    private void scheduleScan(View root, String source) {
        if (root == null) {
            return;
        }
        try {
            installPreDrawScan(root, source);
            scanAndHide(root, source);
            root.post(() -> scanAndHide(root, source));
            root.postDelayed(() -> scanAndHide(root, source), 80L);
            root.postDelayed(() -> scanAndHide(root, source), 250L);
        } catch (Throwable ignored) {
        }
    }

    private void installPreDrawScan(View root, String source) {
        ViewTreeObserver observer = root.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            return;
        }
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                try {
                    ViewTreeObserver current = root.getViewTreeObserver();
                    if (current != null && current.isAlive()) {
                        current.removeOnPreDrawListener(this);
                    }
                } catch (Throwable ignored) {
                }
                scanAndHide(root, source);
                return true;
            }
        });
    }

    private void scanAndHide(View root, String source) {
        try {
            View reaction = findReactionContainer(root);
            if (reaction != null) {
                removeReactionView(reaction, source);
                return;
            }
            ViewGroup row = findReactionRow(root);
            if (row == null) {
                return;
            }
            View target = removableWrapper(row);
            int identity = System.identityHashCode(row);
            if (!hiddenReactionRows.add(identity)) {
                return;
            }
            target.setVisibility(View.GONE);
            SelfCheckRegistry.markSuppressed(FEATURE_REACTION_ROW, source,
                    "hidden " + target.getClass().getName() + " row=" + row.getClass().getName()
                            + " children=" + row.getChildCount());
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, source, throwable);
        }
    }

    private View findReactionContainer(View view) {
        if (view == null) {
            return null;
        }
        if (isReactionContainer(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findReactionContainer(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean isReactionContainer(View view) {
        String name = view.getClass().getName();
        return REACTION_PICKER_CLASS.equals(name) || REACTION_SCROLL_CLASS.equals(name);
    }

    private void removeReactionView(View view, String source) {
        int identity = System.identityHashCode(view);
        if (!hiddenReactionRows.add(identity)) {
            return;
        }
        view.setVisibility(View.GONE);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            params.height = 0;
            params.width = 0;
            view.setLayoutParams(params);
        }
        view.setMinimumHeight(0);
        view.setMinimumWidth(0);
        view.setPadding(0, 0, 0, 0);
        SelfCheckRegistry.markSuppressed(FEATURE_REACTION_ROW, source,
                "removed " + view.getClass().getName());
    }

    private View removableWrapper(ViewGroup row) {
        View target = row;
        Object parent = row.getParent();
        while (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            if (visibleChildCount(group) != 1) {
                break;
            }
            target = group;
            parent = group.getParent();
        }
        return target;
    }

    private int visibleChildCount(ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() == View.VISIBLE) {
                count++;
            }
        }
        return count;
    }

    private ViewGroup findReactionRow(View view) {
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        if (looksLikeReactionRow(group)) {
            return group;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findReactionRow(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean looksLikeReactionRow(ViewGroup group) {
        if (group.getVisibility() != View.VISIBLE) {
            return false;
        }
        int visibleChildren = 0;
        int childCenterYMin = Integer.MAX_VALUE;
        int childCenterYMax = Integer.MIN_VALUE;
        int alphaTextChildren = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            visibleChildren++;
            childCenterYMin = Math.min(childCenterYMin, child.getTop() + child.getHeight() / 2);
            childCenterYMax = Math.max(childCenterYMax, child.getTop() + child.getHeight() / 2);
            if (hasAlphabeticText(child)) {
                alphaTextChildren++;
            }
        }
        if (visibleChildren < 5 || visibleChildren > 8 || alphaTextChildren > 1) {
            return false;
        }
        int width = group.getWidth();
        int height = group.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        float density = group.getResources().getDisplayMetrics().density;
        int minHeight = Math.round(44f * density);
        int maxHeight = Math.round(120f * density);
        int screenWidth = group.getResources().getDisplayMetrics().widthPixels;
        if (height < minHeight || height > maxHeight) {
            return false;
        }
        if (width < screenWidth * 0.55f || width < height * 4) {
            return false;
        }
        return childCenterYMax - childCenterYMin <= height * 0.45f;
    }

    private boolean hasAlphabeticText(View view) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            if (text != null) {
                for (int i = 0; i < text.length(); i++) {
                    if (Character.isLetter(text.charAt(i))) {
                        return true;
                    }
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasAlphabeticText(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }
}
