package com.ez.zalopatch;

/**
 * Decides when the module may send another catalog resolve request.
 *
 * <p>A {@code 426 module_too_old} answer is a negative answer for this exact host artifact and
 * module build, so the retry deadline is keyed to both. Repeated reconciles, lifecycle re-entry and
 * process restarts read the persisted deadline again and stay quiet until it expires. A different
 * base APK, host version or module build produces a different key and may retry immediately.
 *
 * <p>This policy only schedules network requests. It never authorizes symbols, admits a profile or
 * disables feature code; a rejected catalog request is not evidence that every tweak failed.
 */
final class CatalogFetchPolicy {
    private CatalogFetchPolicy() {
    }

    /**
     * Identity the retry deadline belongs to. An empty base hash cannot prove the artifact stayed
     * the same, so it produces no cooldown key and therefore never blocks a later request.
     */
    static String cooldownKey(String baseApkSha256, long hostVersionCode, int moduleVersionCode) {
        if (baseApkSha256 == null || baseApkSha256.isEmpty()) return "";
        return baseApkSha256 + ':' + hostVersionCode + ':' + moduleVersionCode;
    }

    static long retryAt(long now, long intervalMs) {
        return now + intervalMs;
    }

    static boolean coolingDown(long now, long retryAt, String storedKey, String currentKey) {
        return retryAt > now
                && storedKey != null && !storedKey.isEmpty()
                && storedKey.equals(currentKey);
    }

    static boolean shouldFetch(boolean instrumentationPresent, boolean generationChanged,
                               boolean moduleVersionChanged, boolean intervalElapsed,
                               boolean catalogEntryMissing, boolean knownAbsent,
                               boolean coolingDown) {
        if (instrumentationPresent || coolingDown) return false;
        return generationChanged
                || moduleVersionChanged
                || intervalElapsed
                || (catalogEntryMissing && !knownAbsent);
    }
}
