package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * DexKit fingerprint for the chat big-file expiry state.
 *
 * <p>Zalo marks large chat files expired through an enum carrying
 * {@code BIG_FILE_EXPIRED} / {@code BIG_FILE_NOT_EXPIRED} and a static classifier
 * method that returns the current state. Keeping an on-device file usable after expiry
 * is a matter of mapping that classifier result back to not-expired, so both anchors
 * must resolve unambiguously.
 *
 * <p>Dependency-free: candidate shapes arrive as plain strings; uniqueness decides.
 */
public final class DexKitMediaFingerprint {
    public static final String QUERY_STATE_ENUM = "media.state_enum";

    public static final String STATE_EXPIRED = "BIG_FILE_EXPIRED";

    public static final String STATE_NOT_EXPIRED = "BIG_FILE_NOT_EXPIRED";

    public static final String ANCHOR_STATE_CLASS = "symbols.media.state_class";

    public static final String ANCHOR_CLASSIFIER_CLASS = "symbols.media.state_classifier_class";

    public static final String ANCHOR_CLASSIFIER_METHOD = "symbols.media.state_classifier_method";

    private DexKitMediaFingerprint() {
    }

    /** One candidate enum, with the state names it declares. */
    public static final class Candidate {
        public final String className;
        public final List<String> declaredStates;

        public Candidate(String className, List<String> declaredStates) {
            this.className = className == null ? "" : className;
            this.declaredStates = declaredStates == null
                    ? new ArrayList<String>() : new ArrayList<>(declaredStates);
        }
    }

    /** Resolution outcome: the state enum class, or a reason. */
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
     * Picks the unique candidate declaring both expiry states. Zero or several matching
     * candidates stay unavailable: the classifier rewrite must target exactly one enum.
     */
    public static Resolution evaluate(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new Resolution("", "no_state_enum");
        }
        List<Candidate> matching = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.className.isEmpty()) {
                continue;
            }
            if (candidate.declaredStates.contains(STATE_EXPIRED)
                    && candidate.declaredStates.contains(STATE_NOT_EXPIRED)) {
                matching.add(candidate);
            }
        }
        if (matching.isEmpty()) {
            return new Resolution("", "no_expiry_states");
        }
        if (matching.size() > 1) {
            return new Resolution("", "ambiguous_state_enum");
        }
        return new Resolution(matching.get(0).className, "resolved");
    }

    /** One candidate classifier, with the state names it reads. */
    public static final class ClassifierCandidate {
        public final String ownerClass;
        public final String methodName;
        public final List<String> usedStates;

        public ClassifierCandidate(String ownerClass, String methodName, List<String> usedStates) {
            this.ownerClass = ownerClass == null ? "" : ownerClass;
            this.methodName = methodName == null ? "" : methodName;
            this.usedStates = usedStates == null
                    ? new ArrayList<String>() : new ArrayList<>(usedStates);
        }
    }

    /** Resolution outcome: the classifier method, or a reason. */
    public static final class ClassifierResolution {
        public final String ownerClass;
        public final String methodName;
        public final String status;

        ClassifierResolution(String ownerClass, String methodName, String status) {
            this.ownerClass = ownerClass == null ? "" : ownerClass;
            this.methodName = methodName == null ? "" : methodName;
            this.status = status == null ? "" : status;
        }

        public boolean resolved() {
            return "resolved".equals(status);
        }
    }

    /**
     * Picks the unique static classifier reading both expiry states. Zero or several
     * matching candidates stay unavailable: rewriting the wrong method would remap an
     * unrelated state machine.
     */
    public static ClassifierResolution evaluateClassifier(List<ClassifierCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ClassifierResolution("", "", "no_state_classifier");
        }
        List<ClassifierCandidate> matching = new ArrayList<>();
        for (ClassifierCandidate candidate : candidates) {
            if (candidate == null || candidate.ownerClass.isEmpty()
                    || candidate.methodName.isEmpty()) {
                continue;
            }
            if (candidate.usedStates.contains(STATE_EXPIRED)
                    && candidate.usedStates.contains(STATE_NOT_EXPIRED)) {
                matching.add(candidate);
            }
        }
        if (matching.isEmpty()) {
            return new ClassifierResolution("", "", "no_expiry_reads");
        }
        if (matching.size() > 1) {
            return new ClassifierResolution("", "", "ambiguous_state_classifier");
        }
        ClassifierCandidate only = matching.get(0);
        return new ClassifierResolution(only.ownerClass, only.methodName, "resolved");
    }
}
