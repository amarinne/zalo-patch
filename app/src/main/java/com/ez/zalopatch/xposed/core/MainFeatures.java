package com.ez.zalopatch.xposed.core;

import android.content.Context;

import com.ez.zalopatch.DiagnosticsState;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.RuntimeEnvironmentReporter;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.ZaloArtifactState;
import com.ez.zalopatch.xposed.features.BottomTabsFeature;
import com.ez.zalopatch.xposed.features.BackupPushFeature;
import com.ez.zalopatch.xposed.features.CallRecordingProbeFeature;
import com.ez.zalopatch.xposed.features.CallRecordingFeature;
import com.ez.zalopatch.xposed.features.ChatFeature;
import com.ez.zalopatch.xposed.features.InboxFeature;
import com.ez.zalopatch.xposed.features.InteractionTraceFeature;
import com.ez.zalopatch.xposed.features.MeCleanupFeature;
import com.ez.zalopatch.xposed.features.NotificationFeature;
import com.ez.zalopatch.xposed.features.PasscodeGraceFeature;
import com.ez.zalopatch.xposed.features.RuntimeDiscoveryFeature;
import com.ez.zalopatch.xposed.features.StatusPrivacyFeature;
import com.ez.zalopatch.xposed.features.SymbolSchemaHealthFeature;
import com.ez.zalopatch.xposed.features.TelemetryFeature;
import com.ez.zalopatch.xposed.features.WebLinkExternalizeFeature;
import com.ez.zalopatch.xposed.features.ZcloudBannerFeature;
import com.ez.zalopatch.xposed.features.ZinstantFeature;

import java.util.ArrayList;
import java.util.List;

public final class MainFeatures {
    private static final String FEATURE_RUNTIME_DISCOVERY = "runtime_discovery";
    private static final String FEATURE_SYMBOL_PROBE = "symbol_probe";
    private static final String FEATURE_SYMBOL_FALLBACK = "symbol_fallback";

    private MainFeatures() {
    }

    public static void start(
            ClassLoader classLoader, boolean mainProcess, boolean resourceHooksObserved) {
        Context context = HookConfig.resolveFallbackContextForHooks();
        if (mainProcess) {
            RuntimeEnvironmentReporter.report(context, resourceHooksObserved);
        }
        if (DexKitFamilyResolver.maybeRunComparison(context)) {
            return;
        }
        if (mainProcess) {
            runFeature(new SymbolSchemaHealthFeature(classLoader));
        }
        List<Feature> features = new ArrayList<>();
        features.add(new NotificationFeature(classLoader));
        ZaloArtifactState.Compatibility artifact = ZaloArtifactState.forHooks(context);
        // Telemetry is constructed after overlay resolution below: on an unmapped
        // artifact the DexKit telemetry family can supply the DAO accessors.
        features.add(new InteractionTraceFeature(classLoader));

        SymbolSchema.Active hookActive = artifact.compatible
                ? SymbolSchema.activeForHooks(context) : null;
        SymbolPreflight.Result preflight = artifact.compatible && hookActive != null
                ? SymbolPreflight.inspect(hookActive, classLoader)
                : null;
        // Exact-profile pilot coverage, snapshotted before neighbouring adoption below can
        // replace the preflight result. The DexKit pilot decides from the exact snapshot, the
        // bound cache, or the explicit DexKit-unavailable fallback — never from the
        // post-adoption aggregate.
        boolean exactValid = hookActive != null && hookActive.valid;
        SymbolPreflight.Result exactPreflight = preflight;
        // The exact profile resolved nothing, or no profile covers this release at all. Try the
        // neighbouring releases before giving up: a release that did not move the anchors this
        // module hooks is common, and the alternative is every feature silently off until the
        // version is remapped. Only an artifact that reconciled to `unsupported` may take this
        // path; `pending` and `failed` mean verification has not finished, which is not the same
        // as a version nobody mapped.
        Adopted fallback = null;
        if (preflight == null || preflight.resolved() == 0) {
            if (artifact.compatible || "unsupported".equals(artifact.status)) {
                fallback = adoptNearestResolvingProfile(context, classLoader);
                if (fallback != null) {
                    preflight = fallback.preflight;
                }
            }
        } else if (exactValid) {
            // An exact profile resolved, so adoption never ran: replace the previous run's
            // "neighbouring release" row instead of letting it describe a fallback that is
            // no longer in use.
            SelfCheckRegistry.markStatus(FEATURE_SYMBOL_FALLBACK, "disabled",
                    "exact profile in use",
                    "neighbouring-release adoption not needed while an exact profile resolves",
                    "");
        }
        // DexKit overlay: validated on-device descriptors merged over the neighbouring
        // base (if any), adopted for this process. Families the overlay resolves arm
        // from its preflight even when the fallback profile missed them.
        DexKitFamilyResolver.Result overlay = DexKitFamilyResolver.resolve(context, classLoader,
                exactValid, fallback != null ? fallback.profile : null);
        if (overlay.adopted && overlay.preflight != null) {
            if (preflight == null) {
                // No exact or neighbouring profile resolved, but the overlay validated its
                // own symbol sets. Arm features from that result instead of forcing the
                // unsupported path; unresolved families stay unavailable inside it.
                preflight = overlay.preflight;
            }
            mergeOverlayFamily(preflight, overlay.families);
        }
        features.add(new TelemetryFeature(classLoader,
                artifact.compatible || (overlay.adopted && overlay.families.telemetry)));

        if (preflight != null
                && (artifact.compatible || fallback != null || overlay.adopted)) {
            if (artifact.compatible || fallback != null) {
                markArtifactReady(artifact, fallback);
            } else {
                SelfCheckRegistry.markStatus("zalo_artifact", "stale", "dexkit overlay",
                        "No exact or neighbouring profile resolved; symbols taken from the "
                                + "validated DexKit overlay only", "");
            }
            boolean bottomEnabled = HookConfig.isEnabled(Tweaks.KEY_HIDE_DISCOVERY_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_HIDE_TIMELINE_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_KEEP_GROUP_TAB)
                    || HookConfig.isEnabled(Tweaks.KEY_FORCE_MESSAGES_AS_HOME);
            // Auxiliary tab symbols (pager, home hook, consumers) are validated
            // separately and reported honestly; they never gate the overlay-driven
            // tab state and consumers that are verified working on device.
            if (preflight.bottomTabsSymbols) {
                SelfCheckRegistry.markStatus("bottom_tabs.symbols", "ok", "tab symbols",
                        "main tab, pager, home hook, consumers", "");
            } else {
                SelfCheckRegistry.markStale("bottom_tabs.symbols", "structural preflight",
                        preflight.reason(preflight.bottomTabsSymbolsErrors));
            }
            if (preflight.bottomTabs || !bottomEnabled) {
                features.add(new BottomTabsFeature(classLoader));
            } else {
                SelfCheckRegistry.markStale("bottom_tabs.state", "structural preflight",
                        preflight.reason(preflight.bottomErrors));
                SelfCheckRegistry.markStale("bottom_tabs.consumers", "structural preflight",
                        preflight.reason(preflight.bottomErrors));
            }
            addInbox(features, classLoader, preflight);
            addMeCleanup(features, classLoader, preflight);
            // Helper classes and method-name lists are validated up front and reported
            // honestly; the feature keeps its own per-list arming decisions with
            // hardcoded fallbacks, so this row never gates working suppression.
            if (preflight.zinstantSymbols) {
                SelfCheckRegistry.markStatus("zinstant.symbols", "ok", "helper symbols",
                        "communicator, script helper, method lists", "");
            } else {
                SelfCheckRegistry.markStale("zinstant.symbols", "structural preflight",
                        preflight.reason(preflight.zinstantSymbolsErrors));
            }
            DexKitZinstantResolver.Pilot pilot = DexKitZinstantResolver.selectPilot(context,
                    classLoader, exactValid, exactPreflight, preflight,
                    fallback != null);
            features.add(new ZinstantFeature(classLoader,
                    pilot.messageCompatible, pilot.messageError,
                    pilot.feedCompatible, pilot.feedError,
                    pilot.adBindOverride, pilot.feedBindOverride));
            features.add(new ChatFeature(classLoader, preflight.chatReaction,
                    preflight.reason(preflight.chatReactionErrors)));
            features.add(new ZcloudBannerFeature(classLoader));
            addPasscodeGrace(features, classLoader, preflight);
            addStatusPrivacy(features, classLoader, preflight);
            addWebLinkExternalize(features, classLoader, preflight);
            if (mainProcess) {
                features.add(new BackupPushFeature(classLoader,
                        preflight.backupScheduled,
                        preflight.reason(preflight.backupScheduledErrors)));
            }
            addCallRecording(features, classLoader, preflight);
            features.add(new CallRecordingProbeFeature(classLoader));
        } else {
            SelfCheckRegistry.markStatus("zalo_artifact",
                    "failed".equals(artifact.status) ? "failed" : "stale",
                    "exact artifact profile",
                    artifact.reason, "");
            probeSymbols(classLoader);
        }
        maybeAddRuntimeDiscovery(features, classLoader);

        for (Feature feature : features) {
            runFeature(feature);
        }
    }

    private static void addStatusPrivacy(List<Feature> features, ClassLoader classLoader,
                                         SymbolPreflight.Result preflight) {
        boolean seenEnabled = HookConfig.isEnabled(Tweaks.KEY_BLOCK_SEEN_STATUS);
        boolean typingEnabled = HookConfig.isEnabled(Tweaks.KEY_BLOCK_TYPING_STATUS);
        if (preflight.statusPrivacy || (!seenEnabled && !typingEnabled)) {
            features.add(new StatusPrivacyFeature(classLoader));
            return;
        }
        String reason = preflight.reason(preflight.statusPrivacyErrors);
        if (seenEnabled) {
            SelfCheckRegistry.markStale(Tweaks.KEY_BLOCK_SEEN_STATUS,
                    "structural preflight", reason);
        } else {
            SelfCheckRegistry.markDisabled(Tweaks.KEY_BLOCK_SEEN_STATUS, "seen acknowledgement send");
        }
        if (typingEnabled) {
            SelfCheckRegistry.markStale(Tweaks.KEY_BLOCK_TYPING_STATUS,
                    "structural preflight", reason);
        } else {
            SelfCheckRegistry.markDisabled(Tweaks.KEY_BLOCK_TYPING_STATUS, "typing indicator send");
        }
    }

    private static void addPasscodeGrace(List<Feature> features, ClassLoader classLoader,
                                         SymbolPreflight.Result preflight) {
        boolean enabled = HookConfig.isEnabled(Tweaks.KEY_PASSCODE_GRACE);
        if (preflight.passcodeGrace || !enabled) {
            features.add(new PasscodeGraceFeature(classLoader));
            return;
        }
        SelfCheckRegistry.markStale(Tweaks.KEY_PASSCODE_GRACE_MS,
                "structural preflight", preflight.reason(preflight.passcodeGraceErrors));
    }

    private static void addCallRecording(List<Feature> features, ClassLoader classLoader,
                                         SymbolPreflight.Result preflight) {
        // The stable ZRTC core (PeerJNI + CallCallback) arms whenever the setting is on.
        // Drifted activity and peer-manager letters only gate their optional enrichments
        // inside the feature, never the whole family: refusing to install left the
        // working callback path dead on 26.09.01 (T1a).
        features.add(new CallRecordingFeature(classLoader));
    }

    private static void addWebLinkExternalize(List<Feature> features, ClassLoader classLoader,
                                              SymbolPreflight.Result preflight) {
        boolean enabled = HookConfig.isEnabled(Tweaks.KEY_OPEN_LINKS_EXTERNALLY);
        if (preflight.webviewExternalize || !enabled) {
            features.add(new WebLinkExternalizeFeature(classLoader));
            return;
        }
        SelfCheckRegistry.markStale(Tweaks.KEY_OPEN_LINKS_EXTERNALLY,
                "structural preflight", preflight.reason(preflight.webviewErrors));
    }

    /**
     * Records which anchor families would have resolved when no profile covers the installed Zalo
     * version. Nothing is hooked from a probe. Without it an unmapped version reports only that it
     * is unmapped, so the gate suppresses the evidence needed to decide whether it could be armed.
     */
    private static void probeSymbols(ClassLoader classLoader) {
        try {
            SymbolSchema.Active probe = SymbolSchema.probeProfileForHooks(
                    HookConfig.resolveFallbackContextForHooks());
            if (probe == null) {
                SelfCheckRegistry.markStatus(FEATURE_SYMBOL_PROBE, "ok",
                        "no probe profile available", "", "");
                return;
            }
            SymbolPreflight.Result result = SymbolPreflight.inspect(probe, classLoader);
            // The probe exists to be read in a report, and reports carry target but not detail, so
            // the resolved counts live in target. Both fields are module-generated descriptors.
            SelfCheckRegistry.markStatus(FEATURE_SYMBOL_PROBE, "ok",
                    probe.source + " " + probe.minCode + ": "
                            + result.resolved() + "/" + result.total() + " resolved "
                            + result.breakdown(),
                    "probe only, nothing hooked", "");
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStatus(FEATURE_SYMBOL_PROBE, "ok", "probe unavailable", "",
                    throwable.getClass().getSimpleName());
        }
    }

    /**
     * Takes the nearest bundled profile from another release whose anchors actually resolve here.
     *
     * <p>Candidates are preflighted in versionCode order, nearest first, and the first one to
     * resolve any anchor family is adopted for the rest of this Zalo process. Structural preflight
     * is the whole of the check: it validates that a mapped name exists with the mapped shape, and
     * it cannot prove the name still denotes the class it was mapped from, so a profile is adopted
     * for the families it resolved and every other family stays gated off as usual.
     */
    private static Adopted adoptNearestResolvingProfile(Context context,
                                                        ClassLoader classLoader) {
        try {
            long installed = SymbolSchema.installedZaloVersionCode(context);
            if (installed <= 0L) {
                return null;
            }
            for (SymbolSchema.Active candidate
                    : SymbolSchema.fallbackProfilesForHooks(context, installed)) {
                SymbolPreflight.Result result = SymbolPreflight.inspect(candidate, classLoader);
                if (result.resolved() == 0) {
                    continue;
                }
                SymbolSchema.adoptForHooks(candidate, installed);
                SelfCheckRegistry.markStatus(FEATURE_SYMBOL_FALLBACK, "ok",
                        candidate.source + ": " + result.resolved() + "/" + result.total()
                                + " resolved " + result.breakdown(),
                        "no exact profile resolved; symbols taken from a neighbouring release",
                        "");
                return new Adopted(candidate, result);
            }
            SelfCheckRegistry.markStatus(FEATURE_SYMBOL_FALLBACK, "ok",
                    "no neighbouring profile resolved", "", "");
            return null;
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStatus(FEATURE_SYMBOL_FALLBACK, "ok",
                    "fallback unavailable", "", throwable.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * Arms families the DexKit overlay resolved on top of the fallback preflight.
     * Per-anchor precedence: exact profile (handled before this runs), then validated
     * DexKit descriptors, then neighbouring symbols. Only families with a fingerprint
     * definition merge today; the rest keep their fallback state.
     */
    private static void mergeOverlayFamily(SymbolPreflight.Result preflight,
                                           DexKitFamilyResolver.FamilyStates overlay) {
        if (overlay.statusPrivacy && !preflight.statusPrivacy) {
            preflight.statusPrivacy = true;
            preflight.statusPrivacyErrors.clear();
        }
        if (overlay.webview && !preflight.webviewExternalize) {
            preflight.webviewExternalize = true;
            preflight.webviewErrors.clear();
        }
        if (overlay.zinstantMessage && !preflight.zinstantMessage) {
            preflight.zinstantMessage = true;
            preflight.zinstantMessageErrors.clear();
        }
        if (overlay.zinstantFeed && !preflight.zinstantFeed) {
            preflight.zinstantFeed = true;
            preflight.zinstantFeedErrors.clear();
        }
        if (overlay.passcode && !preflight.passcodeGrace) {
            preflight.passcodeGrace = true;
            preflight.passcodeGraceErrors.clear();
        }
        if (overlay.backup && !preflight.backupScheduled) {
            preflight.backupScheduled = true;
            preflight.backupScheduledErrors.clear();
        }
        if (overlay.bottomTabs && !preflight.bottomTabs) {
            preflight.bottomTabs = true;
            preflight.bottomErrors.clear();
        }
        if (overlay.me && !preflight.me) {
            preflight.me = true;
            preflight.meErrors.clear();
        }
        if (overlay.inboxCategories && !preflight.inboxCategories) {
            preflight.inboxCategories = true;
            preflight.inboxCategoryErrors.clear();
        }
        if (overlay.telemetry && !preflight.telemetryDao) {
            preflight.telemetryDao = true;
            preflight.telemetryDaoErrors.clear();
        }
    }

    /** A neighbouring-release profile that preflighted clean, with the result that chose it. */
    private static final class Adopted {        final SymbolSchema.Active profile;
        final SymbolPreflight.Result preflight;

        Adopted(SymbolSchema.Active profile, SymbolPreflight.Result preflight) {
            this.profile = profile;
            this.preflight = preflight;
        }
    }

    private static void markArtifactReady(ZaloArtifactState.Compatibility artifact,
                                          Adopted fallback) {
        if (fallback != null) {
            SelfCheckRegistry.markStatus("zalo_artifact", "stale", "neighbouring release profile",
                    "No exact profile resolved for the installed Zalo; symbols taken from "
                            + fallback.profile.source + " and gated by preflight", "");
            return;
        }
        if (artifact.signerUnverified()) {
            SelfCheckRegistry.markStatus("zalo_artifact", "ok", "versionCode profile",
                    "Zalo signing certificate differs from the mapped one; provenance unverified, "
                            + "anchors gated by preflight",
                    "");
            return;
        }
        if (artifact.containerUnverified()) {
            SelfCheckRegistry.markStatus("zalo_artifact", "ok", "versionCode and signer profile",
                    "Base APK container differs from the mapped one; anchors gated by preflight",
                    "");
            return;
        }
        if (ZaloArtifactState.EVIDENCE_UNKNOWN.equals(artifact.evidence)) {
            SelfCheckRegistry.markStatus("zalo_artifact", "ok", "versionCode and signer profile",
                    "Match tier not recorded yet; re-check requested", "");
            return;
        }
        SelfCheckRegistry.markStatus("zalo_artifact", "ok", "exact artifact profile",
                "Base APK hash and signer matched the mapped artifact", "");
    }

    private static void addInbox(List<Feature> features, ClassLoader classLoader,
                                 SymbolPreflight.Result preflight) {
        boolean hideMedia = HookConfig.isEnabled(Tweaks.KEY_HIDE_MEDIA_BOX);
        boolean filterCategories = HookConfig.isEnabled(Tweaks.KEY_FILTER_POPOVER_CATEGORIES);
        features.add(new InboxFeature(classLoader,
                !hideMedia || preflight.inboxMedia,
                preflight.reason(preflight.inboxMediaErrors),
                !filterCategories || preflight.inboxCategories,
                preflight.reason(preflight.inboxCategoryErrors),
                preflight.inboxRows,
                preflight.reason(preflight.inboxRowsErrors)));
    }

    private static void addMeCleanup(List<Feature> features, ClassLoader classLoader,
                                     SymbolPreflight.Result preflight) {
        boolean qr = HookConfig.isEnabled(Tweaks.KEY_HIDE_QR_WALLET);
        boolean cloud = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZCLOUD);
        boolean style = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZSTYLE);
        boolean business = HookConfig.isEnabled(Tweaks.KEY_HIDE_ZBUSINESS);
        if (preflight.me || (!qr && !cloud && !style && !business)) {
            features.add(new MeCleanupFeature(classLoader));
            return;
        }
        String reason = preflight.reason(preflight.meErrors);
        markMeItem("me_cleanup.qr_wallet", qr, "QR Wallet", reason);
        markMeItem("me_cleanup.zcloud", cloud, "zCloud", reason);
        markMeItem("me_cleanup.zstyle", style, "zStyle", reason);
        markMeItem("me_cleanup.zbusiness", business, "zBusiness", reason);
        SelfCheckRegistry.markStale("me_cleanup.items", "structural preflight", reason);
        SelfCheckRegistry.markStale("me_cleanup.refresh", "structural preflight", reason);
        SelfCheckRegistry.markStale("me_cleanup.visible_rows", "structural preflight", reason);
    }

    private static void markMeItem(String feature, boolean enabled, String label, String reason) {
        if (enabled) {
            SelfCheckRegistry.markStale(feature, "structural preflight", reason);
        } else {
            SelfCheckRegistry.markDisabled(feature, label);
        }
    }

    private static void maybeAddRuntimeDiscovery(List<Feature> features, ClassLoader classLoader) {
        boolean requested = HookConfig.getRawBoolean(DiagnosticsState.KEY_RUNTIME_DISCOVERY_REQUESTED, false);
        long installedVersionCode = SymbolSchema.installedZaloVersionCode(HookConfig.resolveFallbackContextForHooks());
        long lastVersionCode = HookConfig.getRawLong(DiagnosticsState.KEY_RUNTIME_DISCOVERY_LAST_VERSION_CODE, -1L);
        if (requested && installedVersionCode > 0L && installedVersionCode != lastVersionCode) {
            features.add(new RuntimeDiscoveryFeature(classLoader));
            return;
        }
        String detail = requested
                ? "requested=true installed=" + installedVersionCode + " last=" + lastVersionCode
                : "requested=false installed=" + installedVersionCode + " last=" + lastVersionCode;
        SelfCheckRegistry.markStatus(FEATURE_RUNTIME_DISCOVERY, "disabled",
                "explicit request required", detail, "");
    }

    private static void runFeature(Feature feature) {
        try {
            feature.doHook();
        } catch (Throwable throwable) {
            SelfCheckRegistry.markFailed("feature." + feature.getFeatureName(),
                    feature.getFeatureName(), throwable);
            XpLog.e("ZaloPatch: [" + feature.getFeatureName() + "] failed", throwable);
        }
    }

}
