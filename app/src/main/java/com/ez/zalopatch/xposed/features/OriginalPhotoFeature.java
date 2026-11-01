package com.ez.zalopatch.xposed.features;

import android.os.Bundle;

import com.ez.zalopatch.DexKitOriginalPhotoFingerprint;
import com.ez.zalopatch.DexKitRemoteConfigFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Uses Zalo's own Original state for supported picker and external photo-share routes. */
public final class OriginalPhotoFeature extends Feature {
    private static final String SEND = Tweaks.KEY_PREFER_ORIGINAL_PHOTO;
    private static final String PICKER = SEND + ".picker";
    private static final String SHARE = SEND + ".share";
    private final DexKitOriginalPhotoFingerprint.VideoQualityState selection =
            new DexKitOriginalPhotoFingerprint.VideoQualityState();
    private final java.util.concurrent.atomic.AtomicBoolean qualityDefaultReported =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final boolean compatible;
    private final String compatibilityError;

    public OriginalPhotoFeature(ClassLoader loader, boolean compatible, String compatibilityError) {
        super(loader);
        this.compatible = compatible;
        this.compatibilityError = compatibilityError;
    }
    @Override public String getFeatureName() { return "OriginalPhoto"; }

    @Override public void doHook() {
        if (!HookConfig.isEnabled(SEND)) {
            for (String row : new String[]{SEND, PICKER, SHARE}) SelfCheckRegistry.markDisabled(row, "native photo quality");
            return;
        }
        if (!compatible) {
            for (String row : new String[]{SEND, PICKER, SHARE}) {
                SelfCheckRegistry.markStale(row, "qualified photo symbols", compatibilityError);
            }
            return;
        }
        SymbolSchema.Active schema;
        DexKitOriginalPhotoFingerprint.Binding binding;
        try {
            schema = SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks());
            Map<String, String> symbols = new LinkedHashMap<>();
            for (String anchor : DexKitOriginalPhotoFingerprint.ANCHORS) symbols.put(anchor, schema.string(anchor, ""));
            binding = DexKitOriginalPhotoFingerprint.bind(symbols, classLoader);
        } catch (ReflectiveOperationException | IllegalArgumentException missing) {
            for (String row : new String[]{SEND, PICKER, SHARE}) {
                SelfCheckRegistry.markStale(row, "native photo quality preflight", missing.toString());
            }
            return;
        } catch (Throwable error) {
            for (String row : new String[]{SEND, PICKER, SHARE}) {
                SelfCheckRegistry.markFailed(row, "native photo quality preflight", error);
            }
            return;
        }
        List<XpHooks.Handle> hooks = new ArrayList<>();
        try {
            for (Method policy : new Method[]{binding.gate, binding.eligible, binding.entitlement}) {
                hooks.add(XpHooks.hookMethod(SEND, policy, (XpHooks.After) param -> {
                    if (!param.hasThrowable()) param.setResult(true);
                }));
            }
            hooks.addAll(installQualityDefaults(binding));
            hooks.add(installConfigOverride(schema));
            hooks.add(XpHooks.hookMethod(PICKER, binding.getter, (XpHooks.After) param -> {
                try {
                    if (param.hasThrowable() || !(param.getResult() instanceof Integer)) return;
                    int nativeQuality = (Integer) param.getResult();
                    List<?> selected = (List<?>) binding.selection.get(param.thisObject);
                    int photos = 0, other = 0;
                    if (selected != null) for (Object item : selected) {
                        if (item != null && item.getClass() == binding.media) photos++; else other++;
                    }
                    boolean camera = binding.camera(param.thisObject);
                    boolean prefer = DexKitOriginalPhotoFingerprint.preferInPicker(camera, photos, other);
                    selection.remember(selected, binding.video, prefer, nativeQuality);
                    if (prefer && nativeQuality != binding.originalCode) {
                        SelfCheckRegistry.incrementHit(PICKER, binding.getter.toString(), "native picker quality set to Original");
                        param.setResult(binding.originalCode);
                    }
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(PICKER, "native picker quality", error);
                }
            }));
            hooks.add(XpHooks.hookMethod(PICKER, binding.apply, (XpHooks.Before) param -> {
                try {
                    if (!(param.args[0] instanceof Integer)) return;
                    int requested = (Integer) param.args[0];
                    if (binding.video.isInstance(param.thisObject)) {
                        // Preserve each selected video's native input in a mixed picker.
                        // Weak references release removed items without retaining host sessions.
                        Integer nativeQuality = selection.videoQuality(param.thisObject);
                        if (nativeQuality != null && requested == binding.originalCode) {
                            param.args[0] = nativeQuality;
                        }
                        return;
                    }
                    if (requested != binding.originalCode) {
                        param.args[0] = binding.originalCode;
                        SelfCheckRegistry.incrementHit(SEND, binding.apply.toString(),
                                "native photo quality applied before send");
                    }
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(PICKER, "native video quality preservation", error);
                }
            }));
            hooks.add(XpHooks.hookMethod(SEND, binding.outgoingRun, (XpHooks.Before) param -> {
                try {
                    // The conversion copies this flag into the outgoing photo model. Apply
                    // the native Original state to every media list it carries, matching the
                    // upstream patch that rewrites the flag read.
                    for (List<?> items : DexKitOriginalPhotoFingerprint.mediaLists(param.thisObject)) {
                        preferPhotos(items, binding, SEND);
                    }
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(SEND, "native outgoing photo quality", error);
                }
            }));
            hooks.add(XpHooks.hookMethod(SHARE, binding.share, (XpHooks.Before) param -> {
                try {
                    Bundle original = (Bundle) param.args[0];
                    if (!externalPhotoShare(original)) return;
                    Bundle photos = new Bundle(original);
                    boolean changed = !photos.getBoolean("bol_extra_photo_original", false)
                            || !photos.getBoolean("bol_extra_photo_hd", false);
                    photos.putBoolean("bol_extra_photo_original", true);
                    photos.putBoolean("bol_extra_photo_hd", true);
                    List<?> items = photos.getParcelableArrayList("extra_share_multi_photo_from_media_item_list");
                    if (items != null) preferPhotos(items, binding, SHARE);
                    // Count the invocation, not only the flag flip: a share Zalo already had at
                    // Original would otherwise leave the row looking like the hook never ran.
                    SelfCheckRegistry.incrementHit(SHARE, binding.share.toString(), changed
                            ? "external photo extras set to Original"
                            : "external photo share carried Original extras");
                    param.args[0] = photos;
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(SHARE, "native photo share", error);
                }
            }));
            SelfCheckRegistry.markInstalled(SEND, "source=" + schema.source + " "
                    + binding.outgoingRun, 5);
            SelfCheckRegistry.markInstalled(PICKER, binding.getter.toString(), 2);
            SelfCheckRegistry.markInstalled(SHARE, binding.share.toString(), 1);
        } catch (Throwable error) {
            for (XpHooks.Handle hook : hooks) hook.unhook();
            for (String row : new String[]{SEND, PICKER, SHARE}) SelfCheckRegistry.markFailed(row, "native photo quality attach", error);
        }
    }

    private void preferPhotos(List<?> items, DexKitOriginalPhotoFingerprint.Binding binding,
            String row) throws Exception {
        if (items == null) return;
        for (Object item : items) {
            if (item == null || item.getClass() != binding.media) continue;
            boolean wasOriginal = binding.flagOne.getBoolean(item) && binding.flagTwo.getBoolean(item);
            binding.apply.invoke(item, binding.originalCode);
            if (!binding.flagOne.getBoolean(item) || !binding.flagTwo.getBoolean(item)) {
                throw new IllegalStateException("native Original photo state was rejected");
            }
            if (!wasOriginal) SelfCheckRegistry.incrementHit(row, binding.apply.toString(), "native Original photo state applied before conversion");
        }
    }

    /**
     * The policy class also owns the remembered-quality getters used to seed every picker.
     * Force the photo-quality integer getters to Original so the picker opens on Original
     * even before any item is selected. Only static int getters qualify; the boolean gates
     * are hooked separately and no other shape is touched.
     */
    private List<XpHooks.Handle> installQualityDefaults(
            DexKitOriginalPhotoFingerprint.Binding binding) {
        List<XpHooks.Handle> hooks = new ArrayList<>();
        for (Method method : binding.gate.getDeclaringClass().getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != Integer.TYPE) continue;
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 0
                    && !(parameters.length == 1 && parameters[0] == String.class)) continue;
            method.setAccessible(true);
            hooks.add(XpHooks.hookMethod(SEND, method, (XpHooks.After) param -> {
                if (param.hasThrowable()) return;
                param.setResult(binding.originalCode);
                if (qualityDefaultReported.compareAndSet(false, true)) {
                    SelfCheckRegistry.incrementHit(PICKER, method.toString(),
                            "picker quality default set to Original");
                }
            }));
        }
        return hooks;
    }

    /** Enable Zalo's own original-photo picker entitlement without a server round trip. */
    private XpHooks.Handle installConfigOverride(SymbolSchema.Active schema) throws Exception {
        String ownerName = schema.string(DexKitRemoteConfigFingerprint.ANCHOR_READER_CLASS, "");
        String methodName = schema.string(DexKitRemoteConfigFingerprint.ANCHOR_READER_METHOD, "");
        if (ownerName.isEmpty() || methodName.isEmpty()) {
            throw new NoSuchMethodException("original-photo config reader unavailable");
        }
        Class<?> owner = Class.forName(ownerName, false, classLoader);
        Method reader = owner.getDeclaredMethod(methodName, String.class, Integer.TYPE);
        if (reader.getReturnType() != Integer.TYPE || !Modifier.isStatic(reader.getModifiers())) {
            throw new NoSuchMethodException(ownerName + "#" + methodName + " shape changed");
        }
        reader.setAccessible(true);
        return XpHooks.hookMethod(SEND, reader, (XpHooks.After) param -> {
            try {
                if (param.hasThrowable() || param.args == null || param.args.length != 2
                        || !(param.args[0] instanceof String)) return;
                if (!DexKitRemoteConfigFingerprint.ALLOW_ORIGINAL_KEY.equals(param.args[0])) return;
                param.setResult(1);
                SelfCheckRegistry.incrementHit(SEND, ownerName + "#" + methodName,
                        "original photo entitlement enabled");
            } catch (Throwable error) {
                SelfCheckRegistry.markFailed(SEND, "original photo entitlement", error);
            }
        });
    }

    private static boolean externalPhotoShare(Bundle bundle) {
        return bundle != null && bundle.containsKey("imagePathUri")
                && !bundle.getBoolean("bol_share_in_app", false)
                && !bundle.getBoolean("bol_open_post_feed", false)
                && !bundle.containsKey("EXTRA_VIDEO_INFO")
                && !bundle.containsKey("forwardMessageId")
                && !bundle.containsKey("photoInfo");
    }
}
