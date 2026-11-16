package com.ez.zalopatch.xposed.features;

import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.WrapperListAdapter;
import com.ez.zalopatch.DexKitRecallFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.R;
import com.ez.zalopatch.RecallIdentity;
import com.ez.zalopatch.RecallMarkerStore;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds display metadata through the native time layout; it never edits a host message. */
final class RecallMarker {
    static final String FEATURE = "messages.keep_recalled_text.marker";
    interface IdentityReader { RecallIdentity read(Object message) throws Exception; }
    private final IdentityReader identities;
    private final RecallMarkerStore store;
    private final Method getMessage;
    private final Class<?> recycler;
    private final Method getAdapter;
    private final Method notifyChanged;
    private final String label;
    private final WeakHashMap<View, Boolean> rows = new WeakHashMap<>();
    private final AtomicBoolean rendered = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());

    RecallMarker(ClassLoader loader, Class<?> model, SymbolSchema.Active schema,
                 IdentityReader identities, List<XpHooks.Handle> handles) throws Exception {
        this.identities = identities;
        Context context = HookConfig.resolveFallbackContextForHooks();
        if (context == null) throw new IllegalStateException("recall marker context unavailable");
        store = new RecallMarkerStore(context.getSharedPreferences(
                RecallMarkerStore.PREFERENCES, Context.MODE_PRIVATE));
        Context module = HookConfig.resolveModuleContextForHooks();
        Resources resources = module != null && ReactionEmojiResources.valid(module.getResources())
                ? module.getResources() : ReactionEmojiResources.load(context);
        if (resources == null) throw new IllegalStateException("recall marker resources unavailable");
        label = resources.getString(R.string.zp_recalled_marker);
        Class<?> row = Class.forName(DexKitRecallFingerprint.CHAT_ROW, false, loader);
        if (!View.class.isAssignableFrom(row)) throw new IllegalStateException("recall row changed");
        getMessage = row.getDeclaredMethod("getMessage");
        Method time = row.getDeclaredMethod("getCurrentTimeText");
        Class<?> config = Class.forName(schema.string(DexKitRecallFingerprint.TIME_CONFIG, ""),
                false, loader);
        Method visibility = row.getDeclaredMethod(
                schema.string(DexKitRecallFingerprint.TIME_VISIBILITY, ""), config);
        if (getMessage.getReturnType() != model || time.getReturnType() != String.class
                || visibility.getReturnType() != Boolean.TYPE
                || Modifier.isStatic(getMessage.getModifiers()) || Modifier.isStatic(time.getModifiers())
                || Modifier.isStatic(visibility.getModifiers())) {
            throw new IllegalStateException("recall time layout changed");
        }
        getMessage.setAccessible(true);
        recycler = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
        getAdapter = recycler.getMethod("getAdapter");
        notifyChanged = DexKitRecallFingerprint.adapterRefresh(getAdapter.getReturnType());
        if (notifyChanged == null || !notifyChanged.getName().equals(
                schema.string(DexKitRecallFingerprint.TIME_REFRESH, ""))) {
            throw new IllegalStateException("recall adapter refresh changed");
        }
        notifyChanged.setAccessible(true);
        Class<?> caption = Class.forName(DexKitRecallFingerprint.CAPTION_ROW, false, loader);
        Method draw = caption.getDeclaredMethod(schema.string(DexKitRecallFingerprint.TEXT_DRAW, ""),
                android.graphics.Canvas.class);
        Field recalledStyle = caption.getDeclaredField(
                schema.string(DexKitRecallFingerprint.TEXT_RECALLED, ""));
        if (!row.isAssignableFrom(caption) || draw.getReturnType() != Void.TYPE
                || Modifier.isStatic(draw.getModifiers()) || recalledStyle.getType() != Boolean.TYPE
                || Modifier.isStatic(recalledStyle.getModifiers())) {
            throw new IllegalStateException("recall text style changed");
        }
        recalledStyle.setAccessible(true);
        java.util.Map<XpHooks.HookParam, Boolean> drawStyles = new IdentityHashMap<>();
        handles.add(XpHooks.hookMethod(FEATURE, draw, param -> {
            if (marked((View) param.thisObject)) {
                boolean previous = recalledStyle.getBoolean(param.thisObject);
                synchronized (drawStyles) { drawStyles.put(param, previous); }
                // This view-only flag makes Zalo use its theme's native recalled-text gray.
                recalledStyle.setBoolean(param.thisObject, true);
            }
        }, param -> {
            Boolean previous;
            synchronized (drawStyles) { previous = drawStyles.remove(param); }
            if (previous != null) recalledStyle.setBoolean(param.thisObject, previous);
        }));
        handles.add(XpHooks.hookMethod(FEATURE, time, null, param -> {
            if (!param.hasThrowable() && param.getResult() instanceof String
                    && marked((View) param.thisObject)) {
                param.setResult(RecallMarkerStore.timeLabel((String) param.getResult(), label));
                if (rendered.compareAndSet(false, true)) {
                    SelfCheckRegistry.markSuppressed(FEATURE, "ChatRow time label",
                            "recalled marker rendered without changing stored text");
                }
            }
        }));
        handles.add(XpHooks.hookMethod(FEATURE, visibility, null, param -> {
            View view = (View) param.thisObject;
            synchronized (rows) { rows.put(view, Boolean.TRUE); }
            if (!param.hasThrowable() && marked(view)) param.setResult(true);
        }));
        SelfCheckRegistry.markInstalled(FEATURE, "ChatRow time label and native gray text", 3);
    }

    private boolean marked(View row) throws Exception {
        return store.contains(identities.read(getMessage.invoke(row)));
    }

    void remember(RecallIdentity identity) {
        try {
            if (!store.remember(identity, System.currentTimeMillis())) {
                throw new IllegalStateException("recall marker save failed");
            }
            main.post(() -> refresh(identity));
        } catch (Throwable failure) {
            SelfCheckRegistry.markFailed(FEATURE, "recall marker persistence", failure);
        }
    }

    /** Native rebinding resets cached time width and grouped-row timestamp visibility. */
    private void refresh(RecallIdentity identity) {
        try {
            List<View> snapshot;
            synchronized (rows) { snapshot = new ArrayList<>(rows.keySet()); }
            Set<Object> notified = Collections.newSetFromMap(new IdentityHashMap<>());
            for (View row : snapshot) {
                if (row == null || !row.isAttachedToWindow()
                        || !identity.equals(identities.read(getMessage.invoke(row)))) continue;
                for (ViewParent parent = row.getParent(); parent != null; parent = parent.getParent()) {
                    Object adapter = null;
                    if (parent instanceof AdapterView<?>) {
                        adapter = ((AdapterView<?>) parent).getAdapter();
                        for (int depth = 0; depth < 4 && adapter instanceof WrapperListAdapter; depth++) {
                            adapter = ((WrapperListAdapter) adapter).getWrappedAdapter();
                        }
                    } else if (recycler.isInstance(parent)) {
                        adapter = getAdapter.invoke(parent);
                    }
                    if (adapter != null && notified.add(adapter)) {
                        if (adapter instanceof BaseAdapter) ((BaseAdapter) adapter).notifyDataSetChanged();
                        else notifyChanged.invoke(adapter);
                        break;
                    }
                }
            }
        } catch (Throwable failure) {
            SelfCheckRegistry.markFailed(FEATURE, "recall marker refresh", failure);
        }
    }

}
