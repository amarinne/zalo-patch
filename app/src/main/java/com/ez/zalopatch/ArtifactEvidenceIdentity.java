package com.ez.zalopatch;

/** Reconciled artifact identity, independent of the route adopted in a hook process. */
public final class ArtifactEvidenceIdentity {
    public final String lightweight;
    public final String generation;
    public final int moduleVersion;
    public final String profileHash;

    public ArtifactEvidenceIdentity(String lightweight, String generation, int moduleVersion,
                                    String profileHash) {
        this.lightweight = lightweight;
        this.generation = generation;
        this.moduleVersion = moduleVersion;
        this.profileHash = profileHash;
    }

    public boolean matches(String reportedLightweight, String reportedGeneration,
                           Integer reportedModuleVersion, String reportedProfileHash) {
        return lightweight != null && lightweight.equals(reportedLightweight)
                && generation != null && generation.equals(reportedGeneration)
                && reportedModuleVersion != null && moduleVersion == reportedModuleVersion
                && profileHash != null && profileHash.equals(reportedProfileHash);
    }

    public String epochMaterial() {
        return lightweight + "\n" + generation + "\n" + moduleVersion + "\n" + profileHash;
    }

    /** Existing process admission policy: only the initial schema row may replace a run. */
    public static int processAdmission(String currentEpoch, String storedEpoch,
                                       String storedRunId, String feature, String runId) {
        if (runId == null || runId.isEmpty()) return -3;
        if (!currentEpoch.equals(storedEpoch) || storedRunId.isEmpty()
                || runId.equals(storedRunId) || "symbol_schema".equals(feature)) {
            return 1;
        }
        return 0;
    }
}
