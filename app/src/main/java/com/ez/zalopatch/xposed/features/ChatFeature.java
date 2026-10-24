package com.ez.zalopatch.xposed.features;

import android.app.Dialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.ez.zalopatch.DexKitReactionSendFingerprint;
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
import java.util.WeakHashMap;

import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpReflect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ChatFeature extends Feature {
    private static final String FEATURE_REACTION_ROW = "messages.reaction_row";
    private static final String CHAT_ROW_CLASS = "com.zing.zalo.ui.chat.chatrow.ChatRow";
    private static final String REACTION_PICKER_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerInContextMenuView";
    private static final String REACTION_SCROLL_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionScrollView";
    private static final String REACTION_PICKER_BASE_CLASS =
            "com.zing.zalo.ui.widget.reaction.ReactionPickerView";

    /** Primary self-check row: the send outcome, never the button construction. */
    private static final String FEATURE_CUSTOM_REACTION = Tweaks.KEY_CUSTOM_REACTION;
    /** Separate row for the button surface, so a send failure is never masked. */
    private static final String FEATURE_CUSTOM_REACTION_BUTTON =
            Tweaks.KEY_CUSTOM_REACTION + ".button";
    private static final int CUSTOM_BUTTON_DP = 48;
    private static final int CUSTOM_ROW_RESERVE_DP = 48;

    private final ReactionSendGate reactionSendGate = ReactionSendGate.create();
    private final Map<ViewGroup, String> pickerEntryPoints =
            Collections.synchronizedMap(new WeakHashMap<ViewGroup, String>());
    /** Picker captured per listener instance, so a native expand tap can be rerouted. */
    private final Map<Object, ViewGroup> pickerByListener =
            Collections.synchronizedMap(new WeakHashMap<Object, ViewGroup>());
    private final java.util.Set<Class<?>> expandHookedClasses =
            Collections.synchronizedSet(new java.util.HashSet<Class<?>>());
    private EmojiBindings emojiBindings;
    private boolean emojiSlotReported;

    private final Set<Integer> hiddenReactionRows = Collections.synchronizedSet(new HashSet<>());
    private final Set<Integer> disabledReactionGestures = Collections.synchronizedSet(new HashSet<>());

    private static final String FEATURE_REACTION_SYMBOLS = "messages.reaction_symbols";
    private boolean longPressCompatible = true;
    private String longPressCompatibilityError = "";

    /**
     * Cached armed fields per row runtime class. Same match rule as
     * {@code XpReflect.getBooleanField}: first declared field with this name walking up
     * the hierarchy, resolved once instead of scanned on every long press.
     */
    private static final Map<String, Field> ARMED_FIELDS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ARMED_FIELD_MISSES =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static Field armedFieldFor(Class<?> owner, String name) {
        if (owner == null || name == null || name.isEmpty()) {
            return null;
        }
        String key = owner.getName() + "#" + name;
        Field hit = ARMED_FIELDS.get(key);
        if (hit != null) {
            return hit;
        }
        if (ARMED_FIELD_MISSES.contains(key)) {
            return null;
        }
        Field found = null;
        for (Class<?> current = owner; current != null; current = current.getSuperclass()) {
            for (Field candidate : current.getDeclaredFields()) {
                if (name.equals(candidate.getName())) {
                    found = candidate;
                    break;
                }
            }
            if (found != null) {
                break;
            }
        }
        if (found == null) {
            ARMED_FIELD_MISSES.add(key);
            return null;
        }
        found.setAccessible(true);
        ARMED_FIELDS.put(key, found);
        return found;
    }

    public ChatFeature(ClassLoader classLoader) {
        this(classLoader, true, "");
    }

    public ChatFeature(ClassLoader classLoader, boolean longPressCompatible,
                       String longPressCompatibilityError) {
        super(classLoader);
        this.longPressCompatible = longPressCompatible;
        this.longPressCompatibilityError = longPressCompatibilityError;
    }

    @Override
    public String getFeatureName() {
        return "Chat";
    }

    @Override
    public void doHook() {
        installReactionRowRemoval();
        installCustomReaction();
    }

    private void installReactionRowRemoval() {
        if (!HookConfig.isEnabled(Tweaks.KEY_HIDE_REACTION_ROW)) {
            SelfCheckRegistry.markDisabled(FEATURE_REACTION_ROW, "chat popup surfaces");
            return;
        }
        if (longPressCompatible) {
            SelfCheckRegistry.markStatus(FEATURE_REACTION_SYMBOLS, "ok", "long-press symbols",
                    "void method + boolean armed field on the chat row", "");
        } else {
            SelfCheckRegistry.markStale(FEATURE_REACTION_SYMBOLS, "structural preflight",
                    longPressCompatibilityError);
        }
        int hooked = hookPopupSurfaceScans();
        hooked += hookReactionClasses();
        hooked += hookReactionGestureSuppression();
        if (longPressCompatible) {
            hooked += hookReactionHeartLongPress();
        }
        hooked += hookReactionAddView();
        if (hooked > 0) {
            SelfCheckRegistry.markInstalled(FEATURE_REACTION_ROW, "chat popup surface scan", hooked);
            return;
        }
        SelfCheckRegistry.markStale(FEATURE_REACTION_ROW, "symbols.chat",
                "no popup surface hooked; run chat rendering trace first");
    }

    // ---- Custom reaction: adds an emoji button to the same reaction bar the hide tweak targets.

    private void installCustomReaction() {
        boolean customReactionOn = HookConfig.isEnabled(Tweaks.KEY_CUSTOM_REACTION);
        boolean hideReactionRowOn = HookConfig.isEnabled(Tweaks.KEY_HIDE_REACTION_ROW);
        if (!ReactionSendGate.enabled(customReactionOn, hideReactionRowOn)) {
            String reason = !customReactionOn
                    ? "reaction bar emoji button"
                    : "reaction bar is hidden by Hide reaction row";
            SelfCheckRegistry.markDisabled(FEATURE_CUSTOM_REACTION, reason);
            SelfCheckRegistry.markDisabled(FEATURE_CUSTOM_REACTION_BUTTON, reason);
            return;
        }
        // Bindings come from the qualified DexKit overlay and are re-validated live here,
        // before any hook installs and long before a tap.
        EmojiBindings bindings = EmojiBindings.resolve(classLoader);
        if (bindings == null) {
            return;
        }
        if (measuredDimensionSetter() == null) {
            SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION, "reaction bar hooks",
                    "cannot reserve an owned row for the emoji button");
            return;
        }
        this.emojiBindings = bindings;
        List<XpHooks.Handle> handles = new java.util.ArrayList<>();
        try {
            Class<?> picker = XpReflect.findClass(REACTION_PICKER_CLASS, classLoader);
            Class<?> base = XpReflect.findClass(REACTION_PICKER_BASE_CLASS, classLoader);
            // The exact preflight-qualified constructor: no other overload may receive a
            // button or an empty entry point.
            java.lang.reflect.Constructor<?> constructor = picker.getDeclaredConstructor(
                    Context.class, Boolean.TYPE, String.class, bindings.listenerType,
                    String.class);
            handles.add(XpHooks.hookConstructor(FEATURE_CUSTOM_REACTION_BUTTON, constructor, null,
                    new XpHooks.After() {
                        @Override
                        public void after(XpHooks.HookParam param) {
                            if (param.thisObject instanceof ViewGroup) {
                                attachEmojiButton((ViewGroup) param.thisObject,
                                        constructorEntryPoint(param));
                            }
                        }
                    }));
            Method onMeasure = base.getDeclaredMethod("onMeasure", Integer.TYPE, Integer.TYPE);
            onMeasure.setAccessible(true);
            handles.add(XpHooks.hookMethod(FEATURE_CUSTOM_REACTION_BUTTON, onMeasure,
                    new XpHooks.After() {
                        @Override
                        public void after(XpHooks.HookParam param) {
                            if (param.thisObject instanceof ViewGroup) {
                                reserveEmojiRow((ViewGroup) param.thisObject);
                            }
                        }
                    }));
            Method onLayout = base.getDeclaredMethod("onLayout", Boolean.TYPE, Integer.TYPE,
                    Integer.TYPE, Integer.TYPE, Integer.TYPE);
            onLayout.setAccessible(true);
            handles.add(XpHooks.hookMethod(FEATURE_CUSTOM_REACTION_BUTTON, onLayout,
                    new XpHooks.After() {
                        @Override
                        public void after(XpHooks.HookParam param) {
                            if (param.thisObject instanceof ViewGroup) {
                                layoutEmojiButton((ViewGroup) param.thisObject);
                            }
                        }
                    }));
            SelfCheckRegistry.markInstalled(FEATURE_CUSTOM_REACTION_BUTTON,
                    REACTION_PICKER_CLASS + " constructor + onMeasure/onLayout", handles.size());
            publishOutcome();
        } catch (Throwable throwable) {
            for (XpHooks.Handle handle : handles) {
                handle.unhook();
            }
            this.emojiBindings = null;
            SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION, "reaction bar hooks",
                    "cannot hook the reaction bar: " + throwable.getClass().getSimpleName());
        }
    }

    /** The entry point string the host passed to the picker constructor. */
    private static String constructorEntryPoint(XpHooks.HookParam param) {
        try {
            Object[] args = param.args;
            if (args != null && args.length == 5 && args[4] instanceof String) {
                return (String) args[4];
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private void attachEmojiButton(ViewGroup picker, String entryPoint) {
        try {
            pickerEntryPoints.put(picker, entryPoint);
            registerExpandRoute(picker);
            if (findEmojiButton(picker) != null) {
                reactionSendGate.buttonReady();
                return;
            }
            Context context = picker.getContext();
            TextView button = new TextView(context);
            button.setTag(new EmojiButton());
            ReactionEmojiDialog.styleFab(button, context);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pickAndSendEmoji(picker);
                }
            });
            int size = dp(context, CUSTOM_BUTTON_DP);
            picker.addView(button, new ViewGroup.LayoutParams(size, size));
            SelfCheckRegistry.incrementHit(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                    "emoji button added");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                    throwable);
        }
        reactionSendGate.buttonReady();
    }

    /**
     * Captures the picker's listener and hooks the concrete listener's native expansion
     * method once per class. The expansion runs only when the custom-reaction tweak is
     * armed, so the native expand affordance can be rerouted here.
     */
    private void registerExpandRoute(ViewGroup picker) {
        EmojiBindings bindings = emojiBindings;
        if (bindings == null) {
            return;
        }
        final Object listener;
        try {
            listener = bindings.listenerOf(picker);
        } catch (Throwable ignored) {
            return;
        }
        if (listener == null || !bindings.listenerType.isInstance(listener)) {
            return;
        }
        pickerByListener.put(listener, picker);
        Class<?> listenerClass = listener.getClass();
        synchronized (expandHookedClasses) {
            if (!expandHookedClasses.add(listenerClass)) {
                return;
            }
        }
        try {
            Method expand = listenerClass.getMethod(bindings.expand.getName(), String.class);
            expand.setAccessible(true);
            XpHooks.hookMethod(FEATURE_CUSTOM_REACTION_BUTTON, expand, new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    // The native expansion opens the Zstyle sheet; it must not run at all
                    // while this tweak is armed. The host has already dismissed the
                    // picker at this point, so the captured listener carries the send.
                    param.setResult(null);
                    Object captured = param.thisObject;
                    ViewGroup routed = captured == null ? null : pickerByListener.get(captured);
                    if (routed == null) {
                        SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION_BUTTON,
                                REACTION_PICKER_CLASS,
                                "native expand suppressed without a captured picker");
                        return;
                    }
                    SelfCheckRegistry.incrementHit(FEATURE_CUSTOM_REACTION_BUTTON,
                            REACTION_PICKER_CLASS, "native expand routed to emoji picker");
                    pickAndSendEmoji(routed, captured, true);
                }
            }, null);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                    "cannot suppress native expand: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Reserves a module-owned extra row at the bottom of the bar by extending the measured
     * height. The native bubble keeps its own rows and touch rects untouched; the button is
     * laid out into this row.
     */
    private void reserveEmojiRow(ViewGroup picker) {
        if (findEmojiButton(picker) == null) {
            return;
        }
        Method setter = measuredDimensionSetter();
        if (setter == null) {
            return;
        }
        try {
            int width = picker.getMeasuredWidth();
            int height = picker.getMeasuredHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            setter.invoke(picker, width,
                    height + dp(picker.getContext(), CUSTOM_ROW_RESERVE_DP));
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                    throwable);
        }
    }

    /** Places the label pill inside the reserved row, centered on the native bubble width. */
    private void layoutEmojiButton(ViewGroup picker) {
        View button = findEmojiButton(picker);
        if (button == null) {
            return;
        }
        try {
            Context context = picker.getContext();
            int minSize = dp(context, CUSTOM_BUTTON_DP);
            int width = picker.getWidth();
            int height = picker.getHeight();
            int barWidth = barWidthOf(picker);
            if (width <= 0 || height < minSize) {
                button.setVisibility(View.GONE);
                reportNoEmojiSlot();
                return;
            }
            // The label pill takes only the width it needs inside the native bar, capped by
            // the bar (or picker) so it never grows over the native reaction controls.
            int rowWidth = barWidth > 0 && barWidth < width ? barWidth : width;
            int rowLeft = Math.max((width - rowWidth) / 2, 0);
            button.measure(
                    View.MeasureSpec.makeMeasureSpec(rowWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(minSize, View.MeasureSpec.EXACTLY));
            int buttonWidth = Math.min(Math.max(minSize, button.getMeasuredWidth()), rowWidth);
            int left = rowLeft + Math.max((rowWidth - buttonWidth) / 2, 0);
            int top = height - minSize;
            button.layout(left, top, left + buttonWidth, top + minSize);
            button.setVisibility(View.VISIBLE);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                    throwable);
        }
    }

    private void reportNoEmojiSlot() {
        if (emojiSlotReported) {
            return;
        }
        emojiSlotReported = true;
        SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION_BUTTON, REACTION_PICKER_CLASS,
                "no free slot beside the native reaction bar");
    }

    private void pickAndSendEmoji(ViewGroup picker) {
        pickAndSendEmoji(picker, null, false);
    }

    private void pickAndSendEmoji(ViewGroup picker, Object capturedListener,
                                  boolean detachedAllowed) {
        EmojiBindings bindings = emojiBindings;
        if (bindings == null) {
            failSend("reaction bindings unavailable");
            return;
        }
        final Object listener;
        try {
            listener = capturedListener != null ? capturedListener : bindings.listenerOf(picker);
        } catch (Throwable throwable) {
            failSend("reaction listener unavailable");
            return;
        }
        if (listener == null || !bindings.listenerType.isInstance(listener)) {
            failSend("reaction listener unavailable");
            return;
        }
        try {
            String failure = ReactionEmojiDialog.showFailure(picker.getContext(),
                    new ReactionEmojiDialog.Callback() {
                        @Override
                        public void onEmoji(String emoji) {
                            sendEmoji(picker, listener, emoji, detachedAllowed);
                        }
                    },
                    new Runnable() {
                        @Override
                        public void run() {
                            // Cancelling only dismisses the module dialog; the host menu is
                            // left exactly as it was.
                        }
                    });
            if (failure != null) {
                failSend(ReactionEmojiDialog.describeFailure(failure, listener, detachedAllowed));
            }
        } catch (Throwable throwable) {
            failSend("emoji picker: " + throwable.getClass().getSimpleName());
        }
    }

    /**
     * Sends through the bar's own listener exactly like a tap on a built-in reaction. The
     * reaction is built with the host's own Unicode-emoji factory (type 100, uid 0), and the
     * host stamps its native timestamp when it dispatches; a closed or rebound picker and a
     * changed listener both reject the selection.
     */
    private void sendEmoji(ViewGroup picker, Object listener, String emoji,
                           boolean detachedAllowed) {
        EmojiBindings bindings = emojiBindings;
        if (bindings == null) {
            failSend("reaction bindings unavailable");
            return;
        }
        if (emoji == null || emoji.isEmpty()) {
            failSend("empty emoji");
            return;
        }
        boolean attachedAndShown;
        Object current;
        try {
            attachedAndShown = picker.isAttachedToWindow() && picker.isShown();
            current = bindings.listenerOf(picker);
        } catch (Throwable throwable) {
            attachedAndShown = false;
            current = null;
        }
        if (!ReactionSendGate.canSend(attachedAndShown, current != null && current == listener,
                detachedAllowed)) {
            failSend("reaction picker closed or rebound");
            return;
        }
        try {
            Object reaction = bindings.newReaction(emoji);
            bindings.invokeCallback(listener, reaction, entryPointOf(picker));
            reactionSendGate.sendSucceeded();
            publishOutcome();
        } catch (Throwable throwable) {
            Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
            failSend(cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : " " + cause.getMessage()));
        }
    }

    private void failSend(String reason) {
        reactionSendGate.sendFailed(reason);
        publishOutcome();
    }

    /** Publishes the send outcome; construction and button hits never reach this row. */
    private void publishOutcome() {
        String status = reactionSendGate.status();
        if (ReactionSendGate.STATUS_ACTIVE.equals(status)) {
            SelfCheckRegistry.markStatus(FEATURE_CUSTOM_REACTION, "active",
                    REACTION_PICKER_CLASS + "#" + DexKitReactionSendFingerprint.LISTENER_GETTER,
                    "emoji reaction handed to the native listener", "");
        } else if (ReactionSendGate.STATUS_FAILED.equals(status)) {
            SelfCheckRegistry.markStatus(FEATURE_CUSTOM_REACTION, "failed", REACTION_PICKER_CLASS,
                    "", reactionSendGate.failureReason());
        } else {
            SelfCheckRegistry.markStatus(FEATURE_CUSTOM_REACTION,
                    ReactionSendGate.STATUS_INSTALLED,
                    REACTION_PICKER_CLASS + "#" + DexKitReactionSendFingerprint.LISTENER_GETTER,
                    "ready for an explicit emoji tap", "");
        }
    }

    private String entryPointOf(ViewGroup picker) {
        String value = pickerEntryPoints.get(picker);
        return value == null ? "" : value;
    }

    private int barWidthOf(ViewGroup picker) {
        EmojiBindings bindings = emojiBindings;
        return bindings == null ? 0 : bindings.barWidth(picker);
    }

    private static View findEmojiButton(ViewGroup picker) {
        for (int i = 0; i < picker.getChildCount(); i++) {
            View child = picker.getChildAt(i);
            if (child != null && child.getTag() instanceof EmojiButton) {
                return child;
            }
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return Math.round(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static volatile Method measuredDimensionSetter;
    private static volatile boolean measuredDimensionSetterResolved;

    /**
     * Stable Android framework API. Extending the picker's measured height reserves the
     * module-owned row; a blocked reflection leaves the feature unavailable instead of
     * crashing the host.
     */
    private static Method measuredDimensionSetter() {
        if (!measuredDimensionSetterResolved) {
            synchronized (ChatFeature.class) {
                if (!measuredDimensionSetterResolved) {
                    try {
                        Method method = View.class.getDeclaredMethod("setMeasuredDimension",
                                Integer.TYPE, Integer.TYPE);
                        method.setAccessible(true);
                        measuredDimensionSetter = method;
                    } catch (Throwable ignored) {
                        measuredDimensionSetter = null;
                    }
                    measuredDimensionSetterResolved = true;
                }
            }
        }
        return measuredDimensionSetter;
    }

    /** Marker tag that identifies the module-owned button among the picker's children. */
    private static final class EmojiButton {
    }

    /** Reflected, preflight-approved bindings resolved once before the hooks install. */
    private static final class EmojiBindings {
        final Class<?> listenerType;
        final Method listenerGetter;
        final Method callback;
        final Class<?> reactionType;
        final Object companionInstance;
        final Method factory;
        final Method barWidth;
        final Method expand;

        private EmojiBindings(Class<?> listenerType, Method listenerGetter, Method callback,
                              Class<?> reactionType, Object companionInstance, Method factory,
                              Method barWidth, Method expand) {
            this.listenerType = listenerType;
            this.listenerGetter = listenerGetter;
            this.callback = callback;
            this.reactionType = reactionType;
            this.companionInstance = companionInstance;
            this.factory = factory;
            this.barWidth = barWidth;
            this.expand = expand;
        }

        static EmojiBindings resolve(ClassLoader loader) {
            Context context = HookConfig.resolveModuleContextForHooks();
            String listenerClass = SymbolSchema.string(context,
                    DexKitReactionSendFingerprint.ANCHOR_LISTENER_CLASS, "");
            String callbackMethod = SymbolSchema.string(context,
                    DexKitReactionSendFingerprint.ANCHOR_CALLBACK_METHOD, "");
            String reactionClass = SymbolSchema.string(context,
                    DexKitReactionSendFingerprint.ANCHOR_REACTION_CLASS, "");
            String factoryMethod = SymbolSchema.string(context,
                    DexKitReactionSendFingerprint.ANCHOR_FACTORY_METHOD, "");
            List<String> errors = new java.util.ArrayList<>();
            if (!DexKitReactionSendFingerprint.check(loader, listenerClass, callbackMethod,
                    reactionClass, factoryMethod, errors)) {
                SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION, "reaction send bindings",
                        errors.isEmpty() ? "reaction send bindings unavailable"
                                : String.join("; ", errors));
                return null;
            }
            try {
                Class<?> listenerType = Class.forName(listenerClass, false, loader);
                Class<?> reactionType = Class.forName(reactionClass, false, loader);
                Field companionField = reactionType.getField(
                        DexKitReactionSendFingerprint.COMPANION_FIELD);
                Class<?> companionType = companionField.getType();
                Object companionInstance = companionField.get(null);
                Method factory = companionType.getMethod(factoryMethod, Integer.TYPE,
                        String.class);
                factory.setAccessible(true);
                Class<?> picker = Class.forName(
                        DexKitReactionSendFingerprint.PICKER_CLASS, false, loader);
                Method getter = picker.getMethod(DexKitReactionSendFingerprint.LISTENER_GETTER);
                getter.setAccessible(true);
                Method callback = listenerType.getMethod(callbackMethod, reactionType,
                        String.class);
                callback.setAccessible(true);
                Method barWidth = picker.getMethod("getReactionBarWidth");
                barWidth.setAccessible(true);
                // The expansion method is derived by live shape from the DexKit-qualified
                // listener. Without a unique match the feature stays unavailable, so the
                // native expand affordance can never fall through to the Zstyle sheet.
                String expandName = DexKitReactionSendFingerprint.expandMethodName(listenerType);
                if (expandName.isEmpty()) {
                    SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION,
                            "reaction send bindings",
                            "listener has no unique native expansion method");
                    return null;
                }
                Method expand = listenerType.getMethod(expandName, String.class);
                expand.setAccessible(true);
                return new EmojiBindings(listenerType, getter, callback, reactionType,
                        companionInstance, factory, barWidth, expand);
            } catch (Throwable throwable) {
                SelfCheckRegistry.markStale(FEATURE_CUSTOM_REACTION, "reaction send bindings",
                        "reflection failed: " + throwable.getClass().getSimpleName());
                return null;
            }
        }

        Object newReaction(String emoji) throws Throwable {
            Object receiver = Modifier.isStatic(factory.getModifiers()) ? null : companionInstance;
            return factory.invoke(receiver,
                    DexKitReactionSendFingerprint.UNKNOWN_EMOJI_TYPE, emoji);
        }

        Object listenerOf(Object picker) throws Throwable {
            return listenerGetter.invoke(picker);
        }

        void invokeCallback(Object listener, Object reaction, String entryPoint) throws Throwable {
            callback.invoke(listener, reaction, entryPoint);
        }

        int barWidth(Object picker) {
            try {
                Object value = barWidth.invoke(picker);
                return value instanceof Integer ? (Integer) value : 0;
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }

    private int hookPopupSurfaceScans() {
        int hooked = 0;
        XpHooks.Before popupBefore = new XpHooks.Before() {
            @Override
            public void before(XpHooks.HookParam param) {
                if (param.thisObject instanceof PopupWindow) {
                    scheduleScan(((PopupWindow) param.thisObject).getContentView(), "PopupWindow");
                }
            }
        };
        XpHooks.After popupAfter = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (param.thisObject instanceof PopupWindow) {
                    scheduleScan(((PopupWindow) param.thisObject).getContentView(), "PopupWindow");
                }
            }
        };
        XpHooks.After dialogAfter = new XpHooks.After() {
            @Override
            public void after(XpHooks.HookParam param) {
                if (!(param.thisObject instanceof Dialog)) {
                    return;
                }
                Dialog dialog = (Dialog) param.thisObject;
                if (dialog.getWindow() != null) {
                    scheduleScan(dialog.getWindow().getDecorView(), "Dialog");
                }
            }
        };
        try {
            XpHooks.hookAllMethods(FEATURE_REACTION_ROW, PopupWindow.class, "showAtLocation",
                    popupBefore, popupAfter);
            hooked++;
            XpHooks.hookAllMethods(FEATURE_REACTION_ROW, PopupWindow.class, "showAsDropDown",
                    popupBefore, popupAfter);
            hooked++;
            XpHooks.hookAllMethods(FEATURE_REACTION_ROW, Dialog.class, "show", null, dialogAfter);
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
        Class<?> clazz = XpReflect.findClassIfExists(REACTION_PICKER_BASE_CLASS, classLoader);
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
                XpHooks.hookMethod(FEATURE_REACTION_ROW, method, new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
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
        Class<?> rowClass = XpReflect.findClassIfExists(CHAT_ROW_CLASS, classLoader);
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
                XpHooks.hookMethod(FEATURE_REACTION_ROW, method, new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) throws Throwable {
                        // Null thisObject throws here exactly as the uncached
                        // XpReflect path did (getClass on null).
                        Field armed = armedFieldFor(param.thisObject.getClass(), armedField);
                        if (armed == null) {
                            throw new NoSuchFieldException(
                                    param.thisObject.getClass().getName()
                                            + "#" + armedField + " not found");
                        }
                        if (!armed.getBoolean(param.thisObject)) {
                            return;
                        }
                        armed.setBoolean(param.thisObject, false);
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
        Class<?> clazz = XpReflect.findClassIfExists(className, classLoader);
        if (clazz == null) {
            return 0;
        }
        try {
            XpHooks.hookAllConstructors(FEATURE_REACTION_ROW, clazz, null, new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
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
            XpHooks.Before removeBefore = new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    removeReactionArgs(param);
                }
            };
            XpHooks.After removeAfter = new XpHooks.After() {
                @Override
                public void after(XpHooks.HookParam param) {
                    removeReactionArgs(param);
                }
            };
            XpHooks.hookAllMethods(FEATURE_REACTION_ROW, ViewGroup.class, "addView",
                    removeBefore, removeAfter);
            return 1;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed(FEATURE_REACTION_ROW, "ViewGroup.addView reaction", throwable);
            return 0;
        }
    }

    private void removeReactionArgs(XpHooks.HookParam param) {
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

    private int hookWindowManagerAddView() {
        Class<?> impl = XpReflect.findClassIfExists("android.view.WindowManagerImpl", classLoader);
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
                XpHooks.Before scanBefore = new XpHooks.Before() {
                    @Override
                    public void before(XpHooks.HookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            scheduleScan((View) param.args[0], "WindowManager.addView");
                        }
                    }
                };
                XpHooks.After scanAfter = new XpHooks.After() {
                    @Override
                    public void after(XpHooks.HookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof View) {
                            scheduleScan((View) param.args[0], "WindowManager.addView");
                        }
                    }
                };
                XpHooks.hookMethod(FEATURE_REACTION_ROW, method, scanBefore, scanAfter);
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
