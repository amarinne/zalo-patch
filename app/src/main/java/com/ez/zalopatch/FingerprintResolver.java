package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Deterministic scorer. Direct use stays a shadow; the DexKit pilot consumes it only through
 * the validated shared predicates in {@link DexKitZinstantFingerprint}. */
public final class FingerprintResolver {
    public static final int NORMAL_THRESHOLD = 85;
    public static final int HIGH_RISK_THRESHOLD = 90;
    public static final int MINIMUM_MARGIN = 15;

    private FingerprintResolver() {
    }

    public static Resolution resolve(String expectedApkSha256, String actualApkSha256,
                               String anchor, boolean highRisk, List<Candidate> input) {
        if (!expectedApkSha256.equals(actualApkSha256)) {
            return Resolution.stale(anchor, "apk_hash_mismatch");
        }
        ArrayList<Candidate> candidates = new ArrayList<>(input);
        Collections.sort(candidates, Comparator
                .comparingInt(Candidate::score).reversed()
                .thenComparing(candidate -> candidate.symbol));
        if (candidates.isEmpty()) {
            return Resolution.stale(anchor, "no_candidates");
        }
        Candidate winner = candidates.get(0);
        int threshold = highRisk ? HIGH_RISK_THRESHOLD : NORMAL_THRESHOLD;
        if (!winner.mandatorySemantic || winner.score() < threshold) {
            return Resolution.stale(anchor, "confidence_below_threshold");
        }
        int runnerUp = candidates.size() > 1 ? candidates.get(1).score() : 0;
        if (winner.score() - runnerUp < MINIMUM_MARGIN) {
            return Resolution.stale(anchor, "ambiguous_margin");
        }
        return new Resolution(anchor, winner.symbol, winner.score(),
                winner.score() - runnerUp, "resolved");
    }

    static final class Candidate {
        final String symbol;
        final boolean stableOwner;
        final boolean mandatorySemantic;
        final boolean methodShape;
        final boolean fieldRelations;
        final boolean frameworkReferences;
        final boolean bothGoldenVersions;
        final int penalty;

        Candidate(String symbol, boolean stableOwner, boolean mandatorySemantic,
                  boolean methodShape, boolean fieldRelations, boolean frameworkReferences,
                  boolean bothGoldenVersions, int penalty) {
            this.symbol = symbol;
            this.stableOwner = stableOwner;
            this.mandatorySemantic = mandatorySemantic;
            this.methodShape = methodShape;
            this.fieldRelations = fieldRelations;
            this.frameworkReferences = frameworkReferences;
            this.bothGoldenVersions = bothGoldenVersions;
            this.penalty = penalty;
        }

        int score() {
            return (stableOwner ? 25 : 0)
                    + (mandatorySemantic ? 25 : 0)
                    + (methodShape ? 20 : 0)
                    + (fieldRelations ? 15 : 0)
                    + (frameworkReferences ? 10 : 0)
                    + (bothGoldenVersions ? 5 : 0)
                    - Math.max(0, penalty);
        }
    }

    public static final class Resolution {
        public final String anchor;
        public final String symbol;
        public final int score;
        public final int margin;
        public final String status;

        Resolution(String anchor, String symbol, int score, int margin, String status) {
            this.anchor = anchor;
            this.symbol = symbol;
            this.score = score;
            this.margin = margin;
            this.status = status;
        }

        static Resolution stale(String anchor, String status) {
            return new Resolution(anchor, "", 0, 0, status);
        }

        String canonical() {
            return anchor + "\t" + symbol + "\t" + score + "\t" + margin + "\t" + status;
        }
    }
}
