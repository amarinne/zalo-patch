package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure pilot-selection and scan-outcome policy for the DexKit rollout.
 *
 * <p>Dependency-free (no Android, no DexKit) so every branch the reviewers flagged is
 * JVM-tested: pending scans disable the pilot instead of leaking neighbouring flags,
 * neighbouring coverage applies only through the explicit DexKit-unavailable fallback,
 * partial entries never partially activate, and query errors never become cached misses.
 * The Zalo-process adapter ({@code DexKitZinstantResolver}) only gathers inputs and applies
 * the returned decision.
 */
public final class DexKitPilotPolicy {
    /** Rescans a bound partial entry this many times before pinning it. */
    public static final int PARTIAL_MAX_SCANS = 3;

    private DexKitPilotPolicy() {
    }

    /** Inputs for one selection. All Premises are precomputed by the caller. */
    public static final class Coverage {
        public boolean messageEnabled;
        public boolean feedEnabled;
        public boolean exactMessage;
        public boolean exactFeed;
        public String exactMessageError = "";
        public String exactFeedError = "";
        public boolean cachePresent;
        public boolean cacheBound;
        public boolean cacheNegative;
        public String cacheReason = "";
        public boolean cacheAdPresent;
        public boolean cacheFeedPresent;
        public boolean cacheAdUsable;
        public boolean cacheFeedUsable;
        public String cacheAdName = "";
        public String cacheFeedName = "";
        public int cacheQueryRevision;
        public long cacheScanDurationMs;
        public int cacheMatchAd;
        public int cacheMatchFeed;
        public String cacheDigest = "";
        public int cachePartialAttempts;
        public boolean dexkitAvailable = true;
        public String unavailableReason = "";
        public boolean neighborAdopted;
        public boolean neighborMessage;
        public boolean neighborFeed;
        public String neighborMessageError = "";
        public String neighborFeedError = "";
    }

    /** Self-check row data. */
    public static final class Row {
        public final String status;
        public final String target;
        public final String detail;
        public final String error;

        public Row(String status, String target, String detail, String error) {
            this.status = status == null ? "" : status;
            this.target = target == null ? "" : target;
            this.detail = detail == null ? "" : detail;
            this.error = error == null ? "" : error;
        }
    }

    /** Complete selection: pilot flags plus the rows and actions the adapter must apply. */
    public static final class Decision {
        public boolean messageCompatible;
        public String messageError = "";
        public String adOverride = "";
        public boolean feedCompatible;
        public String feedError = "";
        public String feedOverride = "";
        public String source = "unavailable";
        public Row cacheRow = new Row("stale", "", "", "");
        public boolean markScanRow;
        public Row scanRow = new Row("stale", "", "", "");
        public boolean kickScan;
        public boolean clearCache;
    }

    /**
     * Selects the pilot source.
     *
     * <p>Order: exact coverage, then bound cache coverage, then — only when DexKit itself is
     * unavailable — the neighbouring fallback, otherwise a pending-disabled pilot while the
     * scan runs. Contradictory cache evidence withholds the pilot without consulting the
     * neighbouring aggregate. Family coherence: every enabled anchor must resolve from the
     * same entry, or the whole entry is rejected.
     */
    public static Decision decide(Coverage coverage) {
        Coverage input = coverage == null ? new Coverage() : coverage;
        Decision decision = new Decision();
        if (!input.messageEnabled && !input.feedEnabled) {
            decision.cacheRow = new Row("disabled", "zinstant pilot", "", "");
            decision.source = "disabled";
            return decision;
        }
        boolean needMessage = input.messageEnabled && !input.exactMessage;
        boolean needFeed = input.feedEnabled && !input.exactFeed;
        if (!needMessage && !needFeed) {
            decision.messageCompatible = input.messageEnabled;
            decision.messageError = input.messageEnabled ? input.exactMessageError : "";
            decision.feedCompatible = input.feedEnabled;
            decision.feedError = input.feedEnabled ? input.exactFeedError : "";
            decision.source = "exact";
            decision.cacheRow = new Row("ok", "standby",
                    "exact profile active; dexkit not needed", "");
            return decision;
        }
        if (input.cacheBound) {
            return decideFromCache(input, decision, needMessage, needFeed);
        }
        if (input.cachePresent) {
            decision.cacheRow = new Row("stale", "superseded",
                    "cached query, module, or host code changed; rescanning", "");
        } else {
            decision.cacheRow = new Row("stale", "no cache",
                    "no dexkit result for this host code yet", "");
        }
        if (input.dexkitAvailable) {
            decidePending(input, decision, needMessage, needFeed);
            decision.kickScan = true;
            return decision;
        }
        decideUnavailableFallback(input, decision, needMessage, needFeed);
        return decision;
    }

    private static Decision decideFromCache(Coverage input, Decision decision,
                                            boolean needMessage, boolean needFeed) {
        String summary = "rev " + input.cacheQueryRevision + " · " + input.cacheScanDurationMs
                + "ms · ad " + input.cacheMatchAd + " feed " + input.cacheMatchFeed
                + " · " + shortDigest(input.cacheDigest);
        if (input.cacheNegative) {
            decision.cacheRow = new Row("stale",
                    input.cacheReason.isEmpty() ? "no_match" : input.cacheReason,
                    "confirmed miss " + summary, "");
            return contradicted(decision, input);
        }
        boolean messageOk = !needMessage;
        boolean feedOk = !needFeed;
        boolean partial = (needMessage && !input.cacheAdPresent)
                || (needFeed && !input.cacheFeedPresent);
        boolean skew = (needMessage && input.cacheAdPresent && !input.cacheAdUsable)
                || (needFeed && input.cacheFeedPresent && !input.cacheFeedUsable);
        if (needMessage) {
            if (input.cacheAdUsable) {
                messageOk = true;
            }
        }
        if (needFeed) {
            if (input.cacheFeedUsable) {
                feedOk = true;
            }
        }
        if (!messageOk || !feedOk) {
            if (skew) {
                decision.clearCache = true;
            } else if (partial && input.cachePartialAttempts < PARTIAL_MAX_SCANS) {
                decision.kickScan = true;
            }
            String suffix = skew ? "; cache cleared, rescan at next restart"
                    : (partial && input.cachePartialAttempts < PARTIAL_MAX_SCANS
                    ? "; rescan " + (input.cachePartialAttempts + 1) + "/" + PARTIAL_MAX_SCANS
                    : (partial ? "; partial retries exhausted" : ""));
            decision.cacheRow = new Row("stale", partial ? "partial_result" : "preflight_failed",
                    "cached descriptors unavailable for every enabled anchor " + summary
                            + suffix, "");
            return contradicted(decision, input);
        }
        decision.messageCompatible = input.messageEnabled;
        decision.adOverride = input.messageEnabled ? input.cacheAdName : "";
        decision.feedCompatible = input.feedEnabled;
        decision.feedOverride = input.feedEnabled ? input.cacheFeedName : "";
        decision.cacheRow = new Row("ok", "dexkit pilot cache", summary, "");
        decision.source = "dexkit_cache";
        return decision;
    }

    private static Decision contradicted(Decision decision, Coverage input) {
        decision.messageCompatible = false;
        decision.messageError = input.messageEnabled
                ? "dexkit confirmed miss; neighbouring bypass withheld" : "";
        decision.feedCompatible = false;
        decision.feedError = input.feedEnabled
                ? "dexkit confirmed miss; neighbouring bypass withheld" : "";
        decision.source = "dexkit_contradicted";
        return decision;
    }

    private static void decidePending(Coverage input, Decision decision,
                                      boolean needMessage, boolean needFeed) {
        decision.messageCompatible = !needMessage && input.messageEnabled;
        decision.messageError = needMessage
                ? "dexkit scan pending; pilot unavailable until a validated cache exists"
                : (input.messageEnabled ? input.exactMessageError : "");
        decision.feedCompatible = !needFeed && input.feedEnabled;
        decision.feedError = needFeed
                ? "dexkit scan pending; pilot unavailable until a validated cache exists"
                : (input.feedEnabled ? input.exactFeedError : "");
        decision.source = "dexkit_pending";
    }

    private static void decideUnavailableFallback(Coverage input, Decision decision,
                                                  boolean needMessage, boolean needFeed) {
        decision.messageCompatible = !needMessage || input.neighborMessage;
        decision.messageError = !needMessage ? (input.messageEnabled ? input.exactMessageError : "")
                : input.neighborMessageError;
        decision.feedCompatible = !needFeed || input.neighborFeed;
        decision.feedError = !needFeed ? (input.feedEnabled ? input.exactFeedError : "")
                : input.neighborFeedError;
        decision.source = input.neighborAdopted ? "neighbouring" : "unavailable";
        decision.markScanRow = true;
        decision.scanRow = new Row("stale",
                input.unavailableReason.isEmpty() ? "scan unavailable" : input.unavailableReason,
                "", "");
    }

    private static String shortDigest(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.length() > 12 ? digest.substring(0, 12) : digest;
    }

    /** Inputs for mapping one finished scan to a cache write or a retryable failure. */
    public static final class ScanInputs {
        public String adStatus = "";
        public String adSymbol = "";
        public String feedStatus = "";
        public String feedSymbol = "";
        public String adQueryError = "";
        public String feedQueryError = "";
        public int matchAd;
        public int matchFeed;
        public long versionCode;
        public String codeDigest = "";
        public long lastUpdateTime;
        public long apkSize;
        public int queryRevision;
        public int resolverFormat;
        public int moduleVersion;
        public long durationMs;
        public long scannedAt;
        public int previousPartialAttempts;
    }

    /** Either a cacheable entry or a retryable failure. Never both. */
    public static final class ScanOutcome {
        public boolean record;
        public DexKitCache.Entry entry;
        public String retryReason = "";
    }

    /**
     * Maps a finished scan. Query/bridge errors are retryable scan failures and must never be
     * cached as semantic misses; only executed queries with resolvable outcomes become entries.
     */
    public static ScanOutcome resolveScanOutcome(ScanInputs inputs) {
        ScanInputs in = inputs == null ? new ScanInputs() : inputs;
        ScanOutcome outcome = new ScanOutcome();
        List<String> errors = new ArrayList<>();
        if (!in.adQueryError.isEmpty()) {
            errors.add("ad:" + in.adQueryError);
        }
        if (!in.feedQueryError.isEmpty()) {
            errors.add("feed:" + in.feedQueryError);
        }
        if (!errors.isEmpty()) {
            outcome.retryReason = "query_failed: " + String.join("; ", errors);
            return outcome;
        }
        String adBind = "resolved".equals(in.adStatus) ? in.adSymbol : "";
        String feedBind = "resolved".equals(in.feedStatus) ? in.feedSymbol : "";
        boolean negative = adBind.isEmpty() && feedBind.isEmpty();
        String reason = negative ? ("ad:" + in.adStatus + " feed:" + in.feedStatus)
                : (!adBind.isEmpty() && !feedBind.isEmpty() ? ""
                : (adBind.isEmpty() ? "feed only; ad " + in.adStatus
                        : "ad only; feed " + in.feedStatus));
        outcome.record = true;
        outcome.entry = new DexKitCache.Entry(in.versionCode, in.codeDigest, in.lastUpdateTime,
                in.apkSize, in.queryRevision, in.resolverFormat, in.moduleVersion,
                adBind, feedBind, negative, reason,
                in.matchAd, in.matchFeed, in.durationMs, in.scannedAt,
                negative ? 0 : (adBind.isEmpty() || feedBind.isEmpty()
                        ? in.previousPartialAttempts + 1 : 0));
        return outcome;
    }

    /** Scope identity binding one retry budget to one host/code/query/module generation. */
    public static String budgetScope(long versionCode, long lastUpdateTime, int queryRevision,
                                     int resolverFormat, int moduleVersion) {
        return versionCode + ":" + lastUpdateTime + ":" + queryRevision + ":"
                + resolverFormat + ":" + moduleVersion;
    }

    /**
     * Evaluates the scan budget for one scope. A scope change is always a fresh budget;
     * an absent scope never authorizes.
     */
    public static DexKitStore.ScanState evaluateBudget(String storedScope, int failures,
                                                       long lastAttempt, long slotMs,
                                                       String scope, long now) {
        if (scope == null || scope.isEmpty()) {
            return new DexKitStore.ScanState(false, failures, "host identity unavailable");
        }
        if (!scope.equals(storedScope)) {
            return new DexKitStore.ScanState(true, 0, "");
        }
        if (failures >= DexKitStore.MAX_FAILURES
                && now - lastAttempt < DexKitStore.FAILURE_BACKOFF_MS) {
            return new DexKitStore.ScanState(false, failures, "retry budget exhausted");
        }
        if (slotMs > 0L && now - slotMs < DexKitStore.SCAN_MARKER_TTL_MS) {
            return new DexKitStore.ScanState(false, failures, "scan already in progress");
        }
        return new DexKitStore.ScanState(true, failures, "");
    }
}
