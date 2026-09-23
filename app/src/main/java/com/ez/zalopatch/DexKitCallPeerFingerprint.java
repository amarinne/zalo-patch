package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * DexKit fingerprint for the ZRTC active-peer manager.
 *
 * <p>The recorder needs the native peer handle, which lives in Java state:
 * a singleton manager holds a container object whose single {@code long} field is the
 * handle, and one of the manager's methods passes that long to a {@code PeerJNI} call.
 * On 260901903 that is {@code lj.a} (accessor {@code a}, container {@code a}, handle
 * {@code e}) — the same field letters as the 260802903 profile, only the manager class
 * moved from {@code zh.a}. The anchor is the class relation: the class that invokes
 * {@code PeerJNI.zrtc_peer_is_in_call} and holds a single-long container.
 *
 * <p>Dependency-free: candidate shapes arrive as plain strings; uniqueness decides.
 */
public final class DexKitCallPeerFingerprint {
    public static final String QUERY_PEER_MANAGER = "call.peer_manager";

    /** Stable ZRTC peer entry the manager calls with the handle. */
    public static final String PEER_JNI_CLASS = "com.vng.zing.vn.zrtc.PeerJNI";

    public static final String PEER_PREDICATE_METHOD = "zrtc_peer_is_in_call";

    public static final String ANCHOR_MANAGER_CLASS = "symbols.call_recording.peer_manager_class";

    public static final String ANCHOR_ACCESSOR = "symbols.call_recording.peer_manager_instance_method";

    public static final String ANCHOR_CONTAINER = "symbols.call_recording.peer_container_field";

    public static final String ANCHOR_HANDLE = "symbols.call_recording.peer_handle_field";

    private DexKitCallPeerFingerprint() {
    }

    /** One candidate manager, with the shapes read live from the loader. */
    public static final class Candidate {
        public final String className;
        public final String accessor;
        public final String containerField;
        public final String handleField;

        public Candidate(String className, String accessor, String containerField,
                         String handleField) {
            this.className = className == null ? "" : className;
            this.accessor = accessor == null ? "" : accessor;
            this.containerField = containerField == null ? "" : containerField;
            this.handleField = handleField == null ? "" : handleField;
        }
    }

    /** Resolution outcome: the manager quadruple, or a reason. */
    public static final class Resolution {
        public final String className;
        public final String accessor;
        public final String containerField;
        public final String handleField;
        public final String status;

        Resolution(String className, String accessor, String containerField, String handleField,
                   String status) {
            this.className = className == null ? "" : className;
            this.accessor = accessor == null ? "" : accessor;
            this.containerField = containerField == null ? "" : containerField;
            this.handleField = handleField == null ? "" : handleField;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Picks the unique candidate carrying a complete quadruple. Zero or several matching
     * candidates stay unavailable: the peer handle must be unambiguous.
     */
    public static Resolution evaluate(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution("", "", "", "", "no_peer_manager");
        }
        List<Candidate> matching = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.className.isEmpty()) {
                continue;
            }
            if (candidate.accessor.isEmpty() || candidate.containerField.isEmpty()
                    || candidate.handleField.isEmpty()) {
                continue;
            }
            matching.add(candidate);
        }
        if (matching.isEmpty()) {
            return new Resolution("", "", "", "", "no_peer_handle_shape");
        }
        if (matching.size() > 1) {
            return new Resolution("", "", "", "", "ambiguous_peer_manager");
        }
        Candidate only = matching.get(0);
        return new Resolution(only.className, only.accessor, only.containerField,
                only.handleField, "resolved");
    }
}
