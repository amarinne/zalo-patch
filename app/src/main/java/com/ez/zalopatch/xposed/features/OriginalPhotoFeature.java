package com.ez.zalopatch.xposed.features;

import android.os.Bundle;

import com.ez.zalopatch.DexKitOriginalPhotoFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;

import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
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
    private volatile WeakReference<Object> lastPicker = new WeakReference<>(null);
    private final ThreadLocal<Boolean> photoPolicy = new ThreadLocal<>();
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
                    if (Boolean.TRUE.equals(photoPolicy.get()) && !param.hasThrowable()) param.setResult(true);
                }));
            }
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
                    lastPicker = new WeakReference<>(param.thisObject);
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
                    // Preserve each selected video's native input in a mixed picker.
                    // Weak references release removed items without retaining host sessions.
                    Integer nativeQuality = selection.videoQuality(param.thisObject);
                    if (binding.video.isInstance(param.thisObject) && nativeQuality != null
                            && ((Integer) param.args[0]) == binding.originalCode) {
                        param.args[0] = nativeQuality;
                    }
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(PICKER, "native video quality preservation", error);
                }
            }));
            hooks.add(XpHooks.hookConstructor(SEND, binding.outgoing, param -> {
                try {
                    Object picker = lastPicker.get();
                    if (picker == null || binding.camera(picker)) return;
                    List<?> selected = (List<?>) binding.selection.get(picker);
                    preferPhotos((List<?>) param.args[1], binding, SEND, true, selected);
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
                    if (items != null) preferPhotos(items, binding, SHARE, false, null);
                    if (changed) SelfCheckRegistry.incrementHit(SHARE, binding.share.toString(), "external photo extras set to Original");
                    param.args[0] = photos;
                } catch (Throwable error) {
                    SelfCheckRegistry.markFailed(SHARE, "native photo share", error);
                }
            }));
            SelfCheckRegistry.markInstalled(SEND, "source=" + schema.source + " " + binding.outgoing, 4);
            SelfCheckRegistry.markInstalled(PICKER, binding.getter.toString(), 2);
            SelfCheckRegistry.markInstalled(SHARE, binding.share.toString(), 1);
        } catch (Throwable error) {
            for (XpHooks.Handle hook : hooks) hook.unhook();
            for (String row : new String[]{SEND, PICKER, SHARE}) SelfCheckRegistry.markFailed(row, "native photo quality attach", error);
        }
    }

    private void preferPhotos(List<?> items, DexKitOriginalPhotoFingerprint.Binding binding,
            String row, boolean requirePickerSelection, List<?> selected) throws Exception {
        if (items == null) return;
        for (Object item : items) {
            // Type alone cannot distinguish camera photos. Outgoing conversion must
            // also use the live non-camera picker selection; single-item callers
            // can add their item after reading the quality getter.
            if (item == null || item.getClass() != binding.media
                    || (requirePickerSelection
                    && !DexKitOriginalPhotoFingerprint.isSelectedPhoto(binding.media, item, selected))) continue;
            boolean wasOriginal = binding.flagOne.getBoolean(item) && binding.flagTwo.getBoolean(item);
            Boolean previousPolicy = photoPolicy.get();
            photoPolicy.set(true);
            try {
                binding.apply.invoke(item, binding.originalCode);
            } finally {
                if (previousPolicy == null) photoPolicy.remove(); else photoPolicy.set(previousPolicy);
            }
            if (!binding.flagOne.getBoolean(item) || !binding.flagTwo.getBoolean(item)) {
                throw new IllegalStateException("native Original photo state was rejected");
            }
            if (!wasOriginal) SelfCheckRegistry.incrementHit(row, binding.apply.toString(), "native Original photo state applied before conversion");
        }
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
