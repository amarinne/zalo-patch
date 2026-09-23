package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * DexKit fingerprint for the ZRTC call callback implementation.
 *
 * <p>Zalo registers a concrete {@code CallCallback} subclass with the native peer, and that
 * subclass overrides every lifecycle method. Hooking the stable base class therefore
 * installs hooks that never receive a call: on 260901903 the implementation is
 * {@code lb2.q1}, and the mapped letter ({@code l92.z1}) is gone from the APK. The stable
 * anchor is the class relation, not a name: the unique subclass of
 * {@code com.vng.zing.vn.zrtc.CallCallback} that overrides at least one observed callback.
 *
 * <p>Dependency-free: candidate class names arrive as plain strings and the live shape is
 * confirmed by the caller; uniqueness decides.
 */
public final class DexKitCallFingerprint {
    public static final String QUERY_CALLBACK_SUBCLASS = "call.callback_subclass";

    /** Stable ZRTC callback base; its name survives because the native layer needs it. */
    public static final String CALLBACK_BASE = "com.vng.zing.vn.zrtc.CallCallback";

    public static final String ANCHOR_CALLBACK_CLASS = "symbols.call_recording.callback_class";

    private DexKitCallFingerprint() {
    }

    /** One candidate subclass, with the observed callbacks it declares. */
    public static final class Candidate {
        public final String className;
        public final List<String> overriddenCallbacks;

        public Candidate(String className, List<String> overriddenCallbacks) {
            this.className = className == null ? "" : className;
            this.overriddenCallbacks = overriddenCallbacks == null
                    ? new ArrayList<String>() : new ArrayList<>(overriddenCallbacks);
        }
    }

    /** Resolution outcome: the callback implementation class, or a reason. */
    public static final class Resolution {
        public final String className;
        public final String status;

        Resolution(String className, String status) {
            this.className = className == null ? "" : className;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Picks the unique subclass that overrides at least one observed callback. Zero or
     * several matching candidates stay unavailable: with more than one implementation the
     * relation no longer identifies the registered callback.
     */
    public static Resolution evaluate(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution("", "no_callback_subclass");
        }
        List<Candidate> matching = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.className.isEmpty()) {
                continue;
            }
            if (candidate.overriddenCallbacks.isEmpty()) {
                continue;
            }
            matching.add(candidate);
        }
        if (matching.isEmpty()) {
            return new Resolution("", "no_overriding_subclass");
        }
        if (matching.size() > 1) {
            return new Resolution("", "ambiguous_callback_subclass");
        }
        return new Resolution(matching.get(0).className, "resolved");
    }
}
