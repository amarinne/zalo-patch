package com.ez.zalopatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Native photo-quality relationships. No enum ordinal or obfuscated defaults are used. */
public final class DexKitOriginalPhotoFingerprint {
    public static final String MEDIA_ITEM = "com.zing.zalo.data.mediapicker.model.MediaItem";
    public static final String SHARE_VIEW = "com.zing.zalo.ui.zviews.share.ShareView";
    public static final String QUALITY_PROPERTY = "getCurrentSelectedQuality()I";
    public static final String CAMERA_PROPERTY = "isOpenFromCamera()Z";
    public static final String REMEMBERED_QUALITY = "LAST_SELECTION_MEDIA_QUALITY_";
    public static final String PHOTO_PROVIDER = "content://.*com.google.android.apps.photos.contentprovider/.*";
    public static final String SHARE_PHOTOS = "flag_share_multi_photo";
    public static final String PREFIX = "symbols.original_photo.";
    public static final String ENUM = PREFIX + "quality_class";
    public static final String CODE = PREFIX + "quality_code_field";
    public static final String APPLY = PREFIX + "apply_quality_method";
    public static final String FLAG_ONE = PREFIX + "quality_flag_one_field";
    public static final String FLAG_TWO = PREFIX + "quality_flag_two_field";
    public static final String POLICY = PREFIX + "policy_class";
    public static final String GATE = PREFIX + "gate_method";
    public static final String ELIGIBLE = PREFIX + "eligible_method";
    public static final String ENTITLEMENT = PREFIX + "entitlement_method";
    public static final String PICKER = PREFIX + "picker_class";
    public static final String GET_QUALITY = PREFIX + "picker_quality_method";
    // Retained in the anchor vocabulary for old cache parsing; new bindings use native saved state.
    public static final String CAMERA = PREFIX + "picker_camera_method";
    public static final String METADATA = PREFIX + "picker_metadata_field";
    public static final String PROPERTY_CLASS = PREFIX + "picker_property_reader_class";
    public static final String PROPERTY_READ = PREFIX + "picker_property_reader_method";
    public static final String CAMERA_STATE = "OPEN_FROM_CAMERA_SAVED_STATE_KEY";
    public static final String SELECTION = PREFIX + "picker_selection_field";
    public static final String OUTGOING = PREFIX + "outgoing_class";
    public static final String SHARE = PREFIX + "share_method";
    public static final List<String> ANCHORS = Arrays.asList(ENUM, CODE, APPLY, FLAG_ONE, FLAG_TWO,
            POLICY, GATE, ELIGIBLE, ENTITLEMENT, PICKER, GET_QUALITY, METADATA, PROPERTY_CLASS, PROPERTY_READ, SELECTION,
            OUTGOING, SHARE);

    private DexKitOriginalPhotoFingerprint() { }

    /** Small, dependency-free shape model shared by discovery and regression tests. */
    public static final class MethodShape {
        public final String owner, name, returns;
        public final List<String> parameters, fields, calls;
        public final boolean isStatic;

        public MethodShape(String owner, String name, String returns, boolean isStatic,
                List<String> parameters, List<String> fields, List<String> calls) {
            this.owner = owner;
            this.name = name;
            this.returns = returns;
            this.isStatic = isStatic;
            this.parameters = new ArrayList<>(parameters);
            this.fields = new ArrayList<>(fields);
            this.calls = new ArrayList<>(calls);
        }
        public String id() { return owner + "#" + name; }
    }

    /** Ambiguity is unavailable, including duplicate qualifying input rows. */
    public static MethodShape unique(List<MethodShape> candidates) {
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    public static boolean isApply(MethodShape method, String enumClass) {
        return MEDIA_ITEM.equals(method.owner) && !method.isStatic
                && "void".equals(method.returns)
                && method.parameters.equals(Arrays.asList("int"))
                && method.fields.contains(enumClass + "#ORIGINAL")
                && method.fields.contains(enumClass + "#HD");
    }

    public static boolean isEligibility(MethodShape method, String policy, String gate) {
        return policy.equals(method.owner) && method.isStatic
                && "boolean".equals(method.returns) && method.parameters.isEmpty()
                && method.calls.size() == 2 && method.calls.contains(policy + "#" + gate)
                && method.calls.stream().allMatch(call -> call.startsWith(policy + "#"));
    }

    /** A getter can run before a single item enters the list, so empty stays native. */
    public static boolean preferInPicker(boolean camera, int photos, int otherMedia) {
        return !camera && photos > 0;
    }

    public static boolean isSelectedPhoto(Class<?> photo, Object item, List<?> selected) {
        if (item == null || item.getClass() != photo || selected == null) return false;
        for (Object candidate : selected) if (candidate == item) return true;
        return false;
    }

    /** Remember only the exact selected objects, without retaining a picker session. */
    public static final class VideoQualityState {
        private final List<SelectedItem> selected = new ArrayList<>();

        public synchronized void remember(List<?> items, Class<?> video,
                boolean prefer, int nativeQuality) {
            selected.removeIf(entry -> entry.item.get() == null);
            if (items == null) return;
            for (Object item : items) {
                if (!video.isInstance(item)) continue;
                selected.removeIf(entry -> entry.item.get() == item);
                if (prefer) selected.add(new SelectedItem(item, nativeQuality));
            }
        }

        public synchronized Integer videoQuality(Object item) {
            for (SelectedItem entry : selected) if (entry.item.get() == item) return entry.nativeVideoQuality;
            return null;
        }

        private static final class SelectedItem {
            final WeakReference<Object> item;
            final Integer nativeVideoQuality;
            SelectedItem(Object item, Integer nativeVideoQuality) {
                this.item = new WeakReference<>(item);
                this.nativeVideoQuality = nativeVideoQuality;
            }
        }
    }

    /** Validate explicit host quality values, never the enum ordinal or first int field. */
    public static boolean validCodes(int standard, int hd, int original) {
        return standard >= 0 && standard < hd && hd < original;
    }

    /** One verified outgoing constructor with an explicit media-list argument. */
    public static Constructor<?> outgoingConstructor(Class<?> type) {
        if (!Runnable.class.isAssignableFrom(type)) return null;
        Constructor<?> found = null;
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            // This is the photo-conversion constructor shape, not the other merged
            // Runnable cases. Drift to a different prototype stays unavailable.
            if (parameters.length != 5 || parameters[0].isPrimitive()
                    || parameters[1] != java.util.ArrayList.class
                    || parameters[2] != int.class || parameters[3].isPrimitive()
                    || parameters[4] != int.class) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    /** Find the property index from the host's own metadata, without a fixed slot. */
    public static int propertyIndex(Field metadata, String signature) throws Exception {
        if (!Modifier.isStatic(metadata.getModifiers()) || !metadata.getType().isArray()
                || metadata.getType().getComponentType().isPrimitive()) return -1;
        metadata.setAccessible(true);
        Object[] properties = (Object[]) metadata.get(null);
        if (properties == null) return -1;
        int found = -1;
        for (int i = 0; i < properties.length; i++) {
            Object property = properties[i];
            if (property == null) continue;
            boolean matching = false;
            for (Class<?> type = property.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != String.class || Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (signature.equals(field.get(property))) matching = true;
                }
            }
            if (!matching) continue;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    /** Read the exact camera saved-state delegate even when R8 removes its getter. */
    public static boolean cameraFromPicker(Object picker, Field metadata, Method read) throws Exception {
        int cameraIndex = propertyIndex(metadata, CAMERA_PROPERTY);
        if (cameraIndex < 0) throw new IllegalArgumentException("camera property metadata missing");
        Object camera = null;
        for (Field field : metadata.getDeclaringClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !read.getDeclaringClass().isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object delegate = field.get(picker);
            if (delegate == null) continue;
            boolean matching = false;
            for (Class<?> type = delegate.getClass(); type != null; type = type.getSuperclass()) {
                for (Field state : type.getDeclaredFields()) {
                    if (Modifier.isStatic(state.getModifiers()) || state.getType().isPrimitive()) continue;
                    state.setAccessible(true);
                    if (CAMERA_STATE.equals(state.get(delegate))) matching = true;
                }
            }
            if (!matching) continue;
            if (camera != null) throw new IllegalArgumentException("ambiguous camera saved state");
            camera = delegate;
        }
        if (camera == null) throw new IllegalArgumentException("camera saved state missing");
        Object value = read.invoke(camera, picker, ((Object[]) metadata.get(null))[cameraIndex]);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException("camera saved state is not boolean");
        return (Boolean) value;
    }

    /** Live shape check used for cache adoption and hook attachment. */
    public static Binding bind(Map<String, String> symbols, ClassLoader loader) throws Exception {
        for (String anchor : ANCHORS) {
            if (symbols.get(anchor) == null || symbols.get(anchor).isEmpty()) {
                throw new ClassNotFoundException("original photo anchor missing: " + anchor);
            }
        }
        Class<?> quality = Class.forName(symbols.get(ENUM), false, loader);
        if (!quality.isEnum()) throw new IllegalArgumentException("photo quality is not an enum");
        Field code = quality.getDeclaredField(symbols.get(CODE));
        if (code.getType() != int.class || Modifier.isStatic(code.getModifiers())) {
            throw new IllegalArgumentException("photo quality code is not an instance int");
        }
        code.setAccessible(true);
        int standard = code.getInt(quality.getField("STANDARD").get(null));
        int hd = code.getInt(quality.getField("HD").get(null));
        int original = code.getInt(quality.getField("ORIGINAL").get(null));
        if (!validCodes(standard, hd, original)) throw new IllegalArgumentException("photo quality codes drifted");
        Class<?> media = Class.forName(MEDIA_ITEM, false, loader);
        Method apply = method(media, symbols.get(APPLY), void.class, false, int.class);
        Field flagOne = instanceField(media, symbols.get(FLAG_ONE), boolean.class);
        Field flagTwo = instanceField(media, symbols.get(FLAG_TWO), boolean.class);
        if (flagOne.equals(flagTwo)) throw new IllegalArgumentException("photo flags alias");
        Class<?> policy = Class.forName(symbols.get(POLICY), false, loader);
        Method gate = method(policy, symbols.get(GATE), boolean.class, true);
        Method eligible = method(policy, symbols.get(ELIGIBLE), boolean.class, true);
        Method entitlement = method(policy, symbols.get(ENTITLEMENT), boolean.class, true);
        if (gate.equals(eligible) || gate.equals(entitlement) || eligible.equals(entitlement)) {
            throw new IllegalArgumentException("photo policy methods alias");
        }
        Class<?> picker = Class.forName(symbols.get(PICKER), false, loader);
        Method getter = method(picker, symbols.get(GET_QUALITY), int.class, false);
        Field metadata = picker.getDeclaredField(symbols.get(METADATA));
        if (propertyIndex(metadata, QUALITY_PROPERTY) < 0 || propertyIndex(metadata, CAMERA_PROPERTY) < 0) {
            throw new IllegalArgumentException("photo picker metadata drifted");
        }
        Method propertyRead = method(Class.forName(symbols.get(PROPERTY_CLASS), false, loader),
                symbols.get(PROPERTY_READ), Object.class, false, Object.class, metadata.getType().getComponentType());
        Field selection = picker.getDeclaredField(symbols.get(SELECTION));
        if (Modifier.isStatic(selection.getModifiers())
                || !List.class.isAssignableFrom(selection.getType())) {
            throw new IllegalArgumentException("photo picker selection is not an instance List");
        }
        selection.setAccessible(true);
        Constructor<?> outgoing = outgoingConstructor(Class.forName(symbols.get(OUTGOING), false, loader));
        if (outgoing == null) throw new NoSuchMethodException("unique photo-list constructor missing");
        Method share = method(Class.forName(SHARE_VIEW, false, loader), symbols.get(SHARE),
                void.class, false, Class.forName("android.os.Bundle", false, loader));
        return new Binding(media, apply, flagOne, flagTwo, gate, eligible, entitlement,
                getter, metadata, propertyRead, selection, outgoing, share, original);
    }

    private static Method method(Class<?> owner, String name, Class<?> returns,
            boolean isStatic, Class<?>... parameters) throws Exception {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " shape changed");
        }
        method.setAccessible(true);
        return method;
    }

    private static Field instanceField(Class<?> owner, String name, Class<?> type) throws Exception {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(owner.getName() + "#" + name + " shape changed");
        }
        field.setAccessible(true);
        return field;
    }

    public static final class Binding {
        public final Class<?> media, video;
        public final Method apply, gate, eligible, entitlement, getter, propertyRead, share;
        public final Field flagOne, flagTwo, selection, metadata;
        public final Constructor<?> outgoing;
        public final int originalCode;
        public boolean camera(Object picker) throws Exception {
            return cameraFromPicker(picker, metadata, propertyRead);
        }
        Binding(Class<?> media, Method apply, Field flagOne, Field flagTwo,
                Method gate, Method eligible, Method entitlement, Method getter, Field metadata, Method propertyRead,
                Field selection, Constructor<?> outgoing, Method share, int originalCode) throws Exception {
            this.media = media;
            this.video = Class.forName("com.zing.zalo.data.mediapicker.model.VideoItem", false, media.getClassLoader());
            if (!media.isAssignableFrom(video) || video == media) {
                throw new IllegalArgumentException("native VideoItem no longer extends MediaItem");
            }
            this.apply = apply; this.flagOne = flagOne; this.flagTwo = flagTwo;
            this.gate = gate; this.eligible = eligible; this.entitlement = entitlement;
            this.getter = getter; this.metadata = metadata; this.propertyRead = propertyRead; this.selection = selection;
            this.outgoing = outgoing; this.share = share; this.originalCode = originalCode;
        }
    }
}
