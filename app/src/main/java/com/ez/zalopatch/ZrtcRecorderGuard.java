package com.ez.zalopatch;

/** Initializes the inspected native recorder before ZRTC publishes it to either audio thread. */
public final class ZrtcRecorderGuard {
    private static boolean loaded;
    private static String loadError;

    private ZrtcRecorderGuard() { }

    public static synchronized void load() {
        if (loaded || loadError != null) return;
        try {
            System.loadLibrary("zalo_recorder_guard");
            loaded = true;
        } catch (LinkageError error) {
            loadError = error.getClass().getSimpleName();
        }
    }

    public static synchronized boolean loaded() { return loaded; }

    public static synchronized int status() {
        load();
        return loaded ? nativePrepare() : -2;
    }

    /** Returns null only after the loaded native build is classified safely. */
    public static synchronized String prepare() {
        int state = status();
        if (!loaded) return "Recorder guard load failed: " + loadError;
        if (state == 1 || state == 2) return null;
        return state == 0 ? "ZRTC native library is not loaded"
                : "Inspected native recorder requires its initialization guard";
    }

    public static int constructions() { return loaded ? nativeConstructions() : 0; }
    private static native int nativePrepare();
    private static native int nativeConstructions();
}
