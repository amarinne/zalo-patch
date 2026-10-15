package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public final class DexKitOriginalPhotoFingerprintTest {
    private static DexKitOriginalPhotoFingerprint.MethodShape method(String owner, String name,
            String returns, boolean isStatic, java.util.List<String> parameters,
            java.util.List<String> fields, java.util.List<String> calls) {
        return new DexKitOriginalPhotoFingerprint.MethodShape(owner, name, returns, isStatic, parameters, fields, calls);
    }

    @Test public void nativeApplyRequiresPhotoCarrierSignatureAndBothSemanticStates() {
        DexKitOriginalPhotoFingerprint.MethodShape apply = method(DexKitOriginalPhotoFingerprint.MEDIA_ITEM,
                "rotated", "void", false, Arrays.asList("int"), Arrays.asList("xx.d#ORIGINAL", "xx.d#HD"), Collections.emptyList());
        assertTrue(DexKitOriginalPhotoFingerprint.isApply(apply, "xx.d"));
        assertFalse(DexKitOriginalPhotoFingerprint.isApply(apply, "old.d"));
        assertFalse(DexKitOriginalPhotoFingerprint.isApply(method("unrelated.Owner", "rotated", "void", false,
                apply.parameters, apply.fields, apply.calls), "xx.d"));
        assertFalse(DexKitOriginalPhotoFingerprint.isApply(method(apply.owner, "rotated", "void", true,
                apply.parameters, apply.fields, apply.calls), "xx.d"));
        assertFalse(DexKitOriginalPhotoFingerprint.isApply(method(apply.owner, "rotated", "void", false,
                Arrays.asList("boolean"), apply.fields, apply.calls), "xx.d"));
    }

    @Test public void duplicatesAndAmbiguityNeverSelectFirstCandidate() {
        DexKitOriginalPhotoFingerprint.MethodShape first = method("photo.Policy", "gate", "boolean", true,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
        assertSame(first, DexKitOriginalPhotoFingerprint.unique(Arrays.asList(first)));
        assertNull(DexKitOriginalPhotoFingerprint.unique(Collections.emptyList()));
        assertNull(DexKitOriginalPhotoFingerprint.unique(Arrays.asList(first, first)));
        assertNull(DexKitOriginalPhotoFingerprint.unique(Arrays.asList(first, method(first.owner, "other", "boolean", true,
                first.parameters, first.fields, first.calls))));
    }

    @Test public void eligibilityMustPairLocalGateAndLocalEntitlement() {
        assertTrue(DexKitOriginalPhotoFingerprint.isEligibility(method("photo.Policy", "eligible", "boolean", true,
                Collections.emptyList(), Collections.emptyList(), Arrays.asList("photo.Policy#gate", "photo.Policy#entitled")), "photo.Policy", "gate"));
        assertFalse(DexKitOriginalPhotoFingerprint.isEligibility(method("photo.Policy", "eligible", "boolean", true,
                Collections.emptyList(), Collections.emptyList(), Arrays.asList("photo.Policy#gate", "global.Billing#entitled")), "photo.Policy", "gate"));
    }

    @Test public void cameraAndVideoOnlySelectionsKeepNativeQuality() {
        assertFalse(DexKitOriginalPhotoFingerprint.preferInPicker(false, 0, 0));
        assertTrue(DexKitOriginalPhotoFingerprint.preferInPicker(false, 2, 0));
        assertTrue(DexKitOriginalPhotoFingerprint.preferInPicker(false, 1, 1));
        assertFalse(DexKitOriginalPhotoFingerprint.preferInPicker(false, 0, 1));
        assertFalse(DexKitOriginalPhotoFingerprint.preferInPicker(true, 2, 0));
        assertFalse(DexKitOriginalPhotoFingerprint.preferInPicker(true, 0, 0));
    }

    @Test public void qualityCodesAreExplicitAndNeedNotEqualOrdinals() {
        assertTrue(DexKitOriginalPhotoFingerprint.validCodes(10, 20, 30));
        assertFalse(DexKitOriginalPhotoFingerprint.validCodes(0, 1, 1));
        assertFalse(DexKitOriginalPhotoFingerprint.validCodes(0, 2, 1));
        assertFalse(DexKitOriginalPhotoFingerprint.validCodes(-1, 1, 2));
    }

    private static class Photo {
        @Override public boolean equals(Object other) { return other instanceof Photo; }
        @Override public int hashCode() { return 1; }
    }
    private static class Video extends Photo { }
    private static class OtherPhoto extends Photo { }

    @Test public void outgoingScopeUsesSelectedObjectIdentityAndExcludesCameraAndSubclasses() {
        java.util.List<Photo> selection = new ArrayList<>();
        Photo selected = new Photo();
        Photo camera = new Photo();
        OtherPhoto unrelated = new OtherPhoto();
        assertFalse(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, selected, selection));
        selection.add(selected);
        selection.add(unrelated);
        assertTrue(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, selected, selection));
        assertFalse(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, camera, selection));
        assertFalse(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, unrelated, selection));
        assertFalse(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, null, selection));
        selection.clear();
        assertFalse(DexKitOriginalPhotoFingerprint.isSelectedPhoto(Photo.class, selected, selection));
    }

    @Test public void mixedPickerRestoresOnlyItsCapturedVideosAndPureVideoClearsOverride() {
        DexKitOriginalPhotoFingerprint.VideoQualityState selection = new DexKitOriginalPhotoFingerprint.VideoQualityState();
        Photo photo = new Photo();
        Video selected = new Video();
        Video unrelated = new Video();
        selection.remember(Arrays.asList(photo, selected), Video.class, true, 10);
        assertEquals(Integer.valueOf(10), selection.videoQuality(selected));
        assertNull(selection.videoQuality(unrelated));
        assertNull(selection.videoQuality(photo));
        selection.remember(Arrays.asList(selected), Video.class, false, 20);
        assertNull(selection.videoQuality(selected));
    }

    private static class PropertySignature {
        private final String signature;
        PropertySignature(String signature) { this.signature = signature; }
    }
    private static class Property extends PropertySignature {
        Property(String signature) { super(signature); }
    }
    private static Object[] properties = {new Property("irrelevant()Z"), new Property(DexKitOriginalPhotoFingerprint.CAMERA_PROPERTY),
            new Property(DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY)};

    @Test public void propertyPositionsComeFromMetadataAndDuplicatesFailClosed() throws Exception {
        try {
            assertEquals(2, DexKitOriginalPhotoFingerprint.propertyIndex(
                    getClass().getDeclaredField("properties"), DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY));
            assertEquals(1, DexKitOriginalPhotoFingerprint.propertyIndex(
                    getClass().getDeclaredField("properties"), DexKitOriginalPhotoFingerprint.CAMERA_PROPERTY));
            properties = new Object[]{new Property(DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY),
                    new Property(DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY)};
            assertEquals(-1, DexKitOriginalPhotoFingerprint.propertyIndex(
                    getClass().getDeclaredField("properties"), DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY));
        } finally {
            properties = new Object[]{new Property("irrelevant()Z"), new Property(DexKitOriginalPhotoFingerprint.CAMERA_PROPERTY),
                    new Property(DexKitOriginalPhotoFingerprint.QUALITY_PROPERTY)};
        }
    }

    private static class NativeState {
        private final Object key;
        Object value;
        NativeState(String key, Object value) { this.key = key; this.value = value; }
        public Object read(Object owner, Object property) { return value; }
    }
    private static class SavedPicker {
        private static final Object[] metadata = {new Property(DexKitOriginalPhotoFingerprint.CAMERA_PROPERTY)};
        private NativeState unrelated = new NativeState("SHOW_FULL_SAVED_STATE_KEY", true);
        private NativeState camera = new NativeState(DexKitOriginalPhotoFingerprint.CAMERA_STATE, false);
    }

    @Test public void cameraUsesExactSavedStateWithoutAGetterAndRejectsMissingOrAmbiguousState() throws Exception {
        SavedPicker picker = new SavedPicker();
        java.lang.reflect.Field metadata = SavedPicker.class.getDeclaredField("metadata");
        java.lang.reflect.Method read = NativeState.class.getDeclaredMethod("read", Object.class, Object.class);
        read.setAccessible(true);
        assertFalse(DexKitOriginalPhotoFingerprint.cameraFromPicker(picker, metadata, read));
        picker.camera.value = true;
        assertTrue(DexKitOriginalPhotoFingerprint.cameraFromPicker(picker, metadata, read));
        picker.camera.value = 1;
        try { DexKitOriginalPhotoFingerprint.cameraFromPicker(picker, metadata, read); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("not boolean")); }
        picker.camera = null;
        try { DexKitOriginalPhotoFingerprint.cameraFromPicker(picker, metadata, read); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("missing")); }
        picker.camera = new NativeState(DexKitOriginalPhotoFingerprint.CAMERA_STATE, false);
        picker.unrelated = new NativeState(DexKitOriginalPhotoFingerprint.CAMERA_STATE, false);
        try { DexKitOriginalPhotoFingerprint.cameraFromPicker(picker, metadata, read); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("ambiguous")); }
    }

    private static class Outgoing implements Runnable {
        Outgoing(Object dispatcher, ArrayList<?> photos, int source, Object receiver, int mode) { }
        Outgoing(int source, java.util.List<?> unrelated, int mode) { }
        @Override public void run() { }
    }
    private static class AmbiguousOutgoing implements Runnable {
        AmbiguousOutgoing(Object dispatcher, ArrayList<?> photos, int source, Object receiver, int mode) { }
        AmbiguousOutgoing(String dispatcher, ArrayList<?> photos, int source, Object receiver, int mode) { }
        @Override public void run() { }
    }
    private static class Unrelated {
        Unrelated(Object dispatcher, ArrayList<?> photos, int source, Object receiver, int mode) { }
    }

    @Test public void outgoingBoundaryRequiresUniqueExplicitPhotoListConstructor() {
        assertNotNull(DexKitOriginalPhotoFingerprint.outgoingConstructor(Outgoing.class));
        assertNull(DexKitOriginalPhotoFingerprint.outgoingConstructor(AmbiguousOutgoing.class));
        assertNull(DexKitOriginalPhotoFingerprint.outgoingConstructor(Unrelated.class));
    }
}
