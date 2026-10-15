package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.ActivityInstrumentationTestCase2;

import androidx.preference.Preference;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class SettingsUiSmokeTest
        extends ActivityInstrumentationTestCase2<StatusActivity> {
    public SettingsUiSmokeTest() {
        super(StatusActivity.class);
    }

    public void testOutcomeObserverDetectsBypassButRejectsRebindAndAbsentTargets() throws Exception {
        StatusActivity activity = getActivity();
        java.util.concurrent.CountDownLatch detected = new java.util.concurrent.CountDownLatch(1);
        java.util.List<String> reports = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        boolean[] bindingCurrent = {true};
        getInstrumentation().runOnMainSync(() -> {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(activity);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            activity.setContentView(layout);
            for (String scenario : new String[]{"bypass", "hidden", "absent", "rebound", "detached", "transparent"}) {
                android.widget.TextView view = new android.widget.TextView(activity);
                view.setText("Fixed test marker");
                layout.addView(view, new android.widget.LinearLayout.LayoutParams(200, 80));
                view.post(() -> {
                    com.ez.zalopatch.xposed.core.UiOutcomeObserver observer =
                            new com.ez.zalopatch.xposed.core.UiOutcomeObserver((feature, route, evidence) -> {
                                reports.add(feature);
                                if ("bypass".equals(feature) && OutcomeEvidence.parse(evidence) != null) detected.countDown();
                            });
                    observer.afterBind(scenario, "me_full_zinstant", view,
                            () -> !"absent".equals(scenario) && (!"rebound".equals(scenario) || bindingCurrent[0]));
                    // Suppression is deliberately bypassed only for the positive regression control.
                    if ("hidden".equals(scenario)) view.setVisibility(android.view.View.GONE);
                    if ("transparent".equals(scenario)) view.setAlpha(0);
                    if ("detached".equals(scenario)) layout.removeView(view);
                    if ("rebound".equals(scenario)) view.postDelayed(() -> bindingCurrent[0] = false, 750);
                });
            }
        });
        assertTrue("Visible bypass must produce a contradiction", detected.await(5, java.util.concurrent.TimeUnit.SECONDS));
        android.os.SystemClock.sleep(300);
        assertEquals(java.util.Collections.singletonList("bypass"), reports);
    }

    public void testDependencyPagesOpenInSingleHost() {
        StatusActivity activity = getActivity();
        open(activity, SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_INBOX));
        assertTrue(current(activity) instanceof SectionActivity.SettingsFragment);

        open(activity, SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_CALLS));
        assertTrue(current(activity) instanceof SectionActivity.SettingsFragment);

        SectionActivity.SettingsFragment backup =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_BACKUP);
        open(activity, backup);
        assertNotNull(backup.findPreference(Tweaks.KEY_BACKUP_FREQUENT_PUSH));
        assertNotNull(backup.findPreference(Tweaks.KEY_BACKUP_PUSH_INTERVAL));

        SectionActivity.SettingsFragment security =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_SECURITY);
        open(activity, security);
        assertNotNull(security.findPreference(Tweaks.KEY_PASSCODE_GRACE));
        assertNotNull(security.findPreference(Tweaks.KEY_PASSCODE_GRACE_MS));

        SectionActivity.SettingsFragment developer =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_DEVELOPER);
        open(activity, developer);
        assertNotNull(developer.findPreference(StatusActivity.INTERNAL_ROOT_ACCESS));
        assertNotNull(developer.findPreference(
                SectionActivity.SettingsFragment.DEVELOPER_RUNTIME_ENVIRONMENT));
        assertNotNull(developer.findPreference("internal.developer_runtime_status"));
        assertNotNull(developer.findPreference("internal.developer_compatibility_catalog"));
        assertNotNull(developer.findPreference(Tweaks.KEY_CALL_RECORDING_PROBE));
        assertEquals("Report a problem",
                developer.findPreference(SectionActivity.SettingsFragment.DEVELOPER_REPORT)
                        .getTitle());

        open(activity, new CompatibilityCatalogActivity.CatalogFragment());
        assertTrue(current(activity) instanceof CompatibilityCatalogActivity.CatalogFragment);

        open(activity, new NotificationFilterActivity.FilterFragment());
        assertTrue(current(activity) instanceof NotificationFilterActivity.FilterFragment);

        open(activity, new DiagnosticReportActivity.ReportFragment());
        assertTrue(current(activity) instanceof DiagnosticReportActivity.ReportFragment);
    }

    /**
     * Guards the settings group look. zp_row_container carries the first/middle/last group shape,
     * and AndroidX Preference.onBindViewHolder manages the RecyclerView item view background
     * itself. Flattening the container onto the layout root therefore silently discards the group
     * corners and every row renders as its own rounded pill, while every other gate still passes.
     */
    public void testRowContainerIsNotTheItemViewRoot() {
        StatusActivity activity = getActivity();
        android.view.View row = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.zp_preference_row, null, false);
        android.view.View container = row.findViewById(R.id.zp_row_container);

        assertNotNull("zp_row_container must exist in the row layout", container);
        assertNotSame(
                "zp_row_container must stay an inner view, not the item view root, or the group"
                        + " corners are clobbered by AndroidX Preference",
                row, container);
        assertTrue("rounded card must receive the item pressed state",
                container.isDuplicateParentStateEnabled());
    }

    public void testTrailingValueStacksBelowTitleOnCompactWidth() {
        StatusActivity activity = getActivity();
        android.content.res.Configuration configuration =
                new android.content.res.Configuration(
                        activity.getResources().getConfiguration());
        configuration.screenWidthDp = 360;
        Context configured = activity.createConfigurationContext(configuration);
        Context compact = new android.view.ContextThemeWrapper(
                configured, R.style.Theme_ZaloPatch);
        android.view.View row = android.view.LayoutInflater.from(compact)
                .inflate(R.layout.zp_preference_row, null, false);
        androidx.preference.PreferenceViewHolder holder =
                androidx.preference.PreferenceViewHolder.createInstanceForTests(row);
        ZpRowPreference preference = PreferenceUi.info(
                compact, "Runtime environment", null).value(
                        "LSPosed · Resource hooks: observed");

        preference.onBindViewHolder(holder);

        android.widget.LinearLayout titleValue = row.findViewById(R.id.zp_row_title_value);
        android.view.View title = row.findViewById(android.R.id.title);
        android.view.View value = row.findViewById(R.id.zp_row_value);
        assertEquals(android.widget.LinearLayout.VERTICAL, titleValue.getOrientation());
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                title.getLayoutParams().width);
        assertEquals(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                value.getLayoutParams().width);
        assertEquals(0, ((android.view.ViewGroup.MarginLayoutParams)
                value.getLayoutParams()).getMarginStart());
    }

    public void testTrailingValueStaysInlineAtRegularWidth() {
        android.content.res.Configuration configuration =
                new android.content.res.Configuration();
        configuration.screenWidthDp = 400;
        assertFalse(ZpRowStyle.shouldStackValue(configuration));
        configuration.screenWidthDp = 399;
        assertTrue(ZpRowStyle.shouldStackValue(configuration));
    }

    public void testLargeTextDoesNotClipRowValuesOrStatusChips() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            for (String language : new String[] {"en", "vi"}) {
                android.content.res.Configuration configuration =
                        new android.content.res.Configuration(
                                activity.getResources().getConfiguration());
                configuration.setLocale(java.util.Locale.forLanguageTag(language));
                configuration.screenWidthDp = 320;
                configuration.fontScale = 2f;
                Context context = new android.view.ContextThemeWrapper(
                        activity.createConfigurationContext(configuration), R.style.Theme_ZaloPatch);
                float density = context.getResources().getDisplayMetrics().density;
                android.view.LayoutInflater inflater = android.view.LayoutInflater.from(context);
                android.view.View row = inflater.inflate(R.layout.zp_preference_row, null, false);
                ZpRowPreference preference = PreferenceUi.info(context,
                        context.getString(R.string.zp_runtime_environment_title), null)
                        .value(context.getString(R.string.zp_runtime_environment_summary,
                                "LSPosed", context.getString(R.string.zp_capability_observed)));
                preference.onBindViewHolder(
                        androidx.preference.PreferenceViewHolder.createInstanceForTests(row));
                measureForText(row, Math.round(288 * density));
                assertTextFullyVisible(row.findViewById(R.id.zp_row_value));

                ZpFlowLayout chips = new ZpFlowLayout(context);
                android.widget.TextView chip = (android.widget.TextView) inflater.inflate(
                        R.layout.zp_status_chip, chips, false);
                chip.setText(context.getString(R.string.zp_status_installed, 12));
                chips.addView(chip);
                measureForText(chips, Math.round(100 * density));
                assertTextFullyVisible(chip);
            }
        });
    }

    private static void measureForText(android.view.View view, int width) {
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(
                        width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(
                        0, android.view.View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private static void assertTextFullyVisible(android.widget.TextView text) {
        android.text.Layout layout = text.getLayout();
        assertNotNull(layout);
        assertTrue("The large-text fixture must need more than one line", layout.getLineCount() > 1);
        int availableWidth = text.getWidth() - text.getCompoundPaddingLeft()
                - text.getCompoundPaddingRight();
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals("Text must not be ellipsized", 0, layout.getEllipsisCount(line));
            assertTrue("Text must fit horizontally", layout.getLineMax(line) <= availableWidth + 1);
        }
        assertEquals(text.getText().length(), layout.getLineEnd(layout.getLineCount() - 1));
        assertTrue("Text must fit vertically", layout.getHeight() <= text.getHeight()
                - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
    }

    public void testRecycledRowClearsMissingSummary() {
        StatusActivity activity = getActivity();
        android.view.View row = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.zp_preference_row, null, false);
        androidx.preference.PreferenceViewHolder holder =
                androidx.preference.PreferenceViewHolder.createInstanceForTests(row);
        ZpRowPreference withSummary = PreferenceUi.info(
                activity, "Runtime status", "8 active · 0 failed · 0 stale");
        ZpRowPreference withoutSummary = PreferenceUi.nav(activity, "Settings backup", null);

        getInstrumentation().runOnMainSync(() -> {
            withSummary.onBindViewHolder(holder);
            withoutSummary.onBindViewHolder(holder);
        });

        android.widget.TextView summary = row.findViewById(android.R.id.summary);
        assertEquals("", summary.getText().toString());
        assertEquals(android.view.View.GONE, summary.getVisibility());
    }

    public void testDiagnosticDescriptionUsesConnectedNonPersistentRow() {
        StatusActivity activity = getActivity();
        android.view.View row = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.zp_preference_diagnostic_description, null, false);
        android.view.View container = row.findViewById(R.id.zp_row_container);
        android.view.View input = row.findViewById(R.id.zp_diagnostic_description_input);

        assertNotNull(container);
        assertNotSame(row, container);
        assertTrue(container.isDuplicateParentStateEnabled());
        assertNotNull(input);
        assertTrue(input.isFocusable());
        assertTrue(input.isFocusableInTouchMode());
        assertEquals(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS,
                ((android.view.ViewGroup) row).getDescendantFocusability());

        ZpDiagnosticDescriptionPreference preference =
                new ZpDiagnosticDescriptionPreference(activity);
        java.util.concurrent.atomic.AtomicReference<String> entered =
                new java.util.concurrent.atomic.AtomicReference<>();
        preference.listener(entered::set);
        assertEquals(R.layout.zp_preference_diagnostic_description,
                preference.getLayoutResource());
        assertFalse(preference.isPersistent());
        assertFalse(preference.isSelectable());

        androidx.preference.PreferenceViewHolder holder =
                androidx.preference.PreferenceViewHolder.createInstanceForTests(row);
        getInstrumentation().runOnMainSync(() -> {
            preference.onBindViewHolder(holder);
            assertTrue(input.requestFocus());
            ((android.widget.EditText) input).setText("Observed issue");
        });
        assertEquals("Observed issue", entered.get());
        assertFalse(row.isFocusable());
    }

    public void testDashboardRestartShowsFallbackSummaryWithRootDenied() {
        StatusActivity activity = getActivity();
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        Map<String, ?> originalRoot = RootAccess.snapshotForTest(activity);
        try {
            assertTrue(ui.edit().clear().commit());
            RootAccess.setForTest(activity, RootAccess.State.DENIED);
            SettingsChanges.markChanged(activity, "test.pending");
            getInstrumentation().runOnMainSync(() -> {
                // Fixture resets must not leave an older hide animation pending.
                activity.findViewById(R.id.zp_apply_bar).animate().cancel();
                activity.refreshApplyBar(false);
            });
            StatusActivity.DashboardFragment fragment =
                    new StatusActivity.DashboardFragment();
            open(activity, fragment);
            Preference restart = fragment.findPreference(StatusActivity.INTERNAL_RESTART);
            Preference root = fragment.findPreference(StatusActivity.INTERNAL_ROOT_ACCESS);
            Preference apply = fragment.findPreference("internal.apply_settings");

            assertNotNull(restart);
            assertNull(apply);
            assertTrue(restart.isVisible());
            assertTrue(restart.isEnabled());
            assertEquals("Root restarts Zalo automatically. Without root, force stop and reopen it.",
                    String.valueOf(restart.getSummary()));
            assertNull(root);
            assertEquals(android.view.View.VISIBLE,
                    activity.findViewById(R.id.zp_apply_bar).getVisibility());
            assertEquals("Settings saved. Restart Zalo to activate.",
                    String.valueOf(((android.widget.TextView) activity.findViewById(
                            R.id.zp_apply_message)).getText()));
            assertEquals("Restart Zalo",
                    String.valueOf(((android.widget.Button) activity.findViewById(
                            R.id.zp_apply_button)).getText()));
            assertTrue(activity.findViewById(R.id.zp_apply_button).isEnabled());
        } finally {
            RootAccess.restoreForTest(activity, originalRoot);
            restorePreferences(ui, originalUi);
        }
    }

    public void testUnknownRuntimeOffersRestartWithoutPendingEdits() throws Exception {
        StatusActivity activity = getActivity();
        SharedPreferences prefs = TweakStore.preferences(activity);
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        try {
            assertTrue(prefs.edit().remove(SettingsStore.PENDING).putString(ConfigSnapshot.KEY, new ConfigSnapshot(
                    java.util.Collections.emptyMap(), "future", 0, BuildConfig.VERSION_CODE + 1).encode()).commit());
            assertTrue(ui.edit().clear().commit());
            getInstrumentation().runOnMainSync(() -> {
                activity.findViewById(R.id.zp_apply_bar).animate().cancel();
                activity.refreshApplyBar(false);
                assertEquals(android.view.View.VISIBLE, activity.findViewById(R.id.zp_apply_bar).getVisibility());
                assertEquals("Settings saved. Restart Zalo to activate.", String.valueOf(
                        ((android.widget.TextView) activity.findViewById(R.id.zp_apply_message)).getText()));
                assertTrue(activity.findViewById(R.id.zp_apply_button).isEnabled());
            });
            assertEquals(0, SettingsChanges.pendingCount(activity));
        } finally {
            restorePreferences(prefs, original);
            restorePreferences(ui, originalUi);
        }
    }

    public void testFailedRootCheckDoesNotOpenAppInfoOrLoseChanges() {
        StatusActivity activity = getActivity();
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        SharedPreferences tweaks = TweakStore.preferences(activity);
        Map<String, ?> originalTweaks = new HashMap<>(tweaks.getAll());
        Map<String, ?> originalRoot = RootAccess.snapshotForTest(activity);
        android.app.Instrumentation.ActivityMonitor appInfoMonitor =
                getInstrumentation().addMonitor(
                        new android.content.IntentFilter(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                        new android.app.Instrumentation.ActivityResult(
                                android.app.Activity.RESULT_OK, null), true);
        try {
            assertTrue(ui.edit().clear().commit());
            assertTrue(tweaks.edit().remove(SettingsStore.PENDING).commit());
            SettingsChanges.markChanged(activity, "test.pending.a");
            SettingsChanges.markChanged(activity, "test.pending.b");
            long generation = SettingsChanges.generation(activity);
            for (RootAccess.State state : new RootAccess.State[] {
                    RootAccess.State.DENIED, RootAccess.State.ABSENT}) {
                RootAccess.setForTest(activity, state);
                // Inject the external result into the production continuation. A real root
                // request could restart Zalo on a rooted phone and leak work past restoration.
                getInstrumentation().runOnMainSync(() -> {
                    activity.finishRestartRootCheck(state);
                    assertTrue(activity.findViewById(R.id.zp_apply_button).isEnabled());
                });
                assertEquals(0, appInfoMonitor.getHits());
                assertEquals(2, SettingsChanges.pendingCount(activity));
                assertEquals(generation, SettingsChanges.generation(activity));
            }
        } finally {
            getInstrumentation().removeMonitor(appInfoMonitor);
            RootAccess.restoreForTest(activity, originalRoot);
            restorePreferences(tweaks, originalTweaks);
            restorePreferences(ui, originalUi);
        }
    }

    public void testFinishingActivityIgnoresRestartRootResult() {
        StatusActivity activity = getActivity();
        Map<String, ?> before = new HashMap<>(SettingsChanges.preferences(activity).getAll());
        getInstrumentation().runOnMainSync(() -> {
            activity.finish();
            activity.finishRestartRootCheck(RootAccess.State.DENIED);
        });
        assertEquals(before, SettingsChanges.preferences(activity).getAll());
    }

    public void testAcceptedToggleSavesImmediatelyAndReversalClearsActivationDifference() throws Exception {
        StatusActivity activity = getActivity();
        SharedPreferences prefs = TweakStore.preferences(activity);
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> original = new HashMap<>(prefs.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        String published = prefs.getString(ConfigSnapshot.KEY, null);
        String key = Tweaks.KEY_HIDE_DISCOVERY_TAB;
        boolean initial = SettingsStore.getBoolean(activity, key);
        try {
            Map<String, String> loaded = new HashMap<>();
            for (Settings.Setting<?> setting : Settings.all()) {
                loaded.put(setting.key, setting.type == Settings.Type.BOOLEAN
                        ? String.valueOf(SettingsStore.getBoolean(activity, setting.key))
                        : String.valueOf(SettingsStore.getInt(activity, setting.key)));
            }
            loaded.put(NotificationRuleStore.MIRROR_KEY, NotificationRuleStore.encode(NotificationRuleStore.load(activity)));
            assertTrue(SettingsChanges.acknowledge(activity, new ConfigSnapshot(loaded,
                    "toggle-runtime-fixture", SettingsChanges.generation(activity), BuildConfig.VERSION_CODE)));
            SectionActivity.SettingsFragment fragment = SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_NAVIGATION);
            open(activity, fragment);
            Preference toggle = fragment.findPreference(key);
            assertNotNull(toggle);
            getInstrumentation().runOnMainSync(() -> {
                assertTrue(toggle.getOnPreferenceChangeListener().onPreferenceChange(toggle, !initial));
                assertEquals(!initial, SettingsStore.getBoolean(activity, key));
                assertEquals(1, SettingsChanges.pendingCount(activity));
                activity.refreshApplyBar(false);
                assertEquals("Restart Zalo", String.valueOf(((android.widget.Button) activity.findViewById(R.id.zp_apply_button)).getText()));
                assertTrue(toggle.getOnPreferenceChangeListener().onPreferenceChange(toggle, initial));
                assertEquals(0, SettingsChanges.pendingCount(activity));
                activity.refreshApplyBar(false);
                assertEquals(android.view.View.GONE, activity.findViewById(R.id.zp_apply_bar).getVisibility());
            });
            assertEquals(published, prefs.getString(ConfigSnapshot.KEY, null));
        } finally {
            restorePreferences(prefs, original);
            restorePreferences(ui, originalUi);
        }
    }

    public void testRestartStateSurvivesActivityRecreationWithoutAnotherWorker() {
        StatusActivity activity = getActivity();
        SettingsRestart[] retained = new SettingsRestart[1];
        android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(
                StatusActivity.class.getName(), null, false);
        StatusActivity recreated = null;
        try {
            getInstrumentation().runOnMainSync(() -> {
                retained[0] = new androidx.lifecycle.ViewModelProvider(activity).get(SettingsRestart.class);
                // Inject a waiting worker state without requesting root or restarting the host.
                retained[0].state.setValue(new SettingsRestart.State(SettingsRestart.Phase.RESTART, null, null));
                assertFalse(activity.findViewById(R.id.zp_apply_button).isEnabled());
                activity.recreate();
            });
            recreated = (StatusActivity) monitor.waitForActivityWithTimeout(5000);
            assertNotNull(recreated);
            StatusActivity current = recreated;
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> {
                assertSame(retained[0], new androidx.lifecycle.ViewModelProvider(current).get(SettingsRestart.class));
                assertFalse(current.findViewById(R.id.zp_apply_button).isEnabled());
                assertEquals(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                        current.findViewById(R.id.zp_settings_content).getImportantForAccessibility());
            });
        } finally {
            StatusActivity current = recreated;
            getInstrumentation().runOnMainSync(() -> {
                if (retained[0] != null) retained[0].state.setValue(new SettingsRestart.State(SettingsRestart.Phase.IDLE, null, null));
                if (current != null) current.finish();
            });
            getInstrumentation().removeMonitor(monitor);
        }
    }

    public void testResourceHookRequirementUsesPendingThenUnavailableRendering() {
        StatusActivity activity = getActivity();
        Map<String, ?> originalRuntime = RuntimeEnvironment.snapshotForTest(activity);
        try {
            RuntimeEnvironment.clearForTest(activity);
            SectionActivity.SettingsFragment pending =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_INBOX);
            open(activity, pending);
            assertTrue(pending.findPreference(Tweaks.KEY_HIDE_ZCLOUD_BANNER)
                    instanceof ZpSwitchPreference);

            long zaloVersion = SymbolSchema.installedZaloVersionCode(activity);
            assertTrue(RuntimeEnvironment.record(activity, "lspatch", "unavailable",
                    BuildConfig.VERSION_CODE, zaloVersion));
            SectionActivity.SettingsFragment unsupported =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_INBOX);
            open(activity, unsupported);
            Preference row = unsupported.findPreference(Tweaks.KEY_HIDE_ZCLOUD_BANNER);
            assertNotNull(row);
            assertFalse(row instanceof ZpSwitchPreference);
            assertFalse(row.isSelectable());
            assertNull(row.getOnPreferenceChangeListener());
            assertTrue(String.valueOf(row.getSummary()).contains(
                    "did not expose resource hooks"));
        } finally {
            RuntimeEnvironment.restoreForTest(activity, originalRuntime);
        }
    }

    public void testTelemetryUsesMainSwitchPatternAndTracksChildren() {
        StatusActivity activity = getActivity();
        String originalDisplayMode = UiSettings.displayMode(activity);
        SharedPreferences tweaks = activity.getSharedPreferences(
                Tweaks.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> originalTweaks = new HashMap<>(tweaks.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        try {
            UiSettings.setDisplayMode(activity, UiSettings.MODE_DETAILED);
            SharedPreferences.Editor seed = tweaks.edit();
            for (String key : Tweaks.TELEMETRY_KEYS) {
                seed.putBoolean(key, true);
            }
            assertTrue(seed.commit());

            SectionActivity.SettingsFragment fragment =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_TELEMETRY);
            open(activity, fragment);
            Preference master = fragment.findPreference("internal.telemetry_master");
            Preference child = fragment.findPreference(Tweaks.KEY_DISABLE_EVENT_ANALYTICS);

            assertTrue(master instanceof ZpMainSwitchPreference);
            assertEquals(R.layout.zp_preference_main_switch, master.getLayoutResource());
            assertTrue(((ZpMainSwitchPreference) master).isChecked());
            assertNotNull(child);
            assertNotNull(child.getOnPreferenceChangeListener());
            String renderedSummary = String.valueOf(child.getSummary());
            SymbolSchema.Active schema = SymbolSchema.active(activity);
            if (schema.valid) {
                String accessor = schema.string("symbols.telemetry.analytics_event_accessor", "");
                String databaseClass = schema.string("symbols.telemetry.analytics_db_class", "");
                String symbol = databaseClass + "#" + accessor + "()";
                assertTrue(renderedSummary.contains(symbol));
                assertTrue(child.getSummary() instanceof android.text.Spanned);
                android.text.Spanned styledSummary = (android.text.Spanned) child.getSummary();
                int symbolStart = renderedSummary.indexOf("#" + accessor + "()");
                android.text.style.ForegroundColorSpan[] symbolColors = styledSummary.getSpans(
                        symbolStart, symbolStart + accessor.length() + 3,
                        android.text.style.ForegroundColorSpan.class);
                assertEquals(1, symbolColors.length);
                assertEquals(androidx.core.content.ContextCompat.getColor(
                                activity, R.color.zp_status_warn),
                        symbolColors[0].getForegroundColor());
            } else {
                assertTrue(renderedSummary.contains(
                        activity.getString(R.string.zp_hook_path_unavailable)));
                assertFalse(renderedSummary.contains("#z()"));
            }
            android.view.View childRow = android.view.LayoutInflater.from(activity)
                    .inflate(R.layout.zp_preference_row, null, false);
            androidx.preference.PreferenceViewHolder childHolder =
                    androidx.preference.PreferenceViewHolder.createInstanceForTests(childRow);
            getInstrumentation().runOnMainSync(() ->
                    ((ZpSwitchPreference) child).onBindViewHolder(childHolder));
            android.view.ViewGroup chips = childRow.findViewById(R.id.zp_row_chips);
            assertEquals(0, chips.getChildCount());

            getInstrumentation().runOnMainSync(() -> assertTrue(
                    child.getOnPreferenceChangeListener().onPreferenceChange(child, false)));
            getInstrumentation().waitForIdleSync();
            assertFalse(((ZpMainSwitchPreference) master).isChecked());

            assertNotNull(master.getOnPreferenceChangeListener());
            getInstrumentation().runOnMainSync(() -> assertTrue(
                    master.getOnPreferenceChangeListener().onPreferenceChange(master, true)));
            getInstrumentation().waitForIdleSync();
            for (String key : Tweaks.TELEMETRY_KEYS) {
                assertTrue(TweakStore.isEnabled(activity, key));
            }
        } finally {
            UiSettings.setDisplayMode(activity, originalDisplayMode);
            restorePreferences(tweaks, originalTweaks);
            restorePreferences(ui, originalUi);
        }
    }

    public void testTelemetryMasterGenerationCannotAcknowledgeUnsavedValues() throws Exception {
        StatusActivity activity = getActivity();
        SharedPreferences tweaks = TweakStore.preferences(activity);
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> originalTweaks = new HashMap<>(tweaks.getAll());
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        ConfigSnapshot[] captured = new ConfigSnapshot[1];
        SharedPreferences.OnSharedPreferenceChangeListener capture = (preferences, key) -> {
            Map<String, String> values = new HashMap<>();
            for (Settings.Setting<?> setting : Settings.all()) {
                values.put(setting.key, setting.type == Settings.Type.BOOLEAN
                        ? String.valueOf(SettingsStore.getBoolean(activity, setting.key))
                        : String.valueOf(SettingsStore.getInt(activity, setting.key)));
            }
            try { values.put(NotificationRuleStore.MIRROR_KEY, NotificationRuleStore.encode(NotificationRuleStore.load(activity))); }
            catch (Exception invalid) { throw new AssertionError(invalid); }
            captured[0] = new ConfigSnapshot(values, "telemetry-writer-test",
                    SettingsChanges.generation(activity), BuildConfig.VERSION_CODE);
        };
        try {
            SharedPreferences.Editor seed = tweaks.edit();
            for (String key : Tweaks.TELEMETRY_KEYS) seed.putBoolean(key, false);
            assertTrue(seed.commit());
            assertTrue(ui.edit().clear().commit());
            SectionActivity.SettingsFragment fragment =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_TELEMETRY);
            open(activity, fragment);
            Preference master = fragment.findPreference("internal.telemetry_master");
            getInstrumentation().runOnMainSync(() -> {
                tweaks.registerOnSharedPreferenceChangeListener(capture);
                try {
                    assertTrue(master.getOnPreferenceChangeListener().onPreferenceChange(master, true));
                } finally {
                    tweaks.unregisterOnSharedPreferenceChangeListener(capture);
                }
            });
            assertNotNull(captured[0]);
            assertEquals(SettingsChanges.generation(activity), captured[0].generation);
            assertTrue(tweaks.edit().putString(ConfigSnapshot.KEY, captured[0].encode()).commit());
            FrameworkConfig.acknowledge(activity, captured[0].id);
            assertEquals(0, SettingsChanges.pendingCount(activity));
            for (String key : Tweaks.TELEMETRY_KEYS) {
                assertTrue(TweakStore.isEnabled(activity, key));
                assertEquals("Cleared generation must describe the saved value for " + key,
                        "true", captured[0].values.get(key));
            }
        } finally {
            restorePreferences(tweaks, originalTweaks);
            restorePreferences(ui, originalUi);
        }
    }

    public void testSimplifiedModeHidesHookDetails() {
        StatusActivity activity = getActivity();
        String originalDisplayMode = UiSettings.displayMode(activity);
        try {
            UiSettings.setDisplayMode(activity, UiSettings.MODE_SIMPLIFIED);
            SectionActivity.SettingsFragment fragment =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_TELEMETRY);
            open(activity, fragment);
            Preference child = fragment.findPreference(Tweaks.KEY_DISABLE_EVENT_ANALYTICS);

            assertNotNull(child);
            assertEquals(activity.getString(R.string.zp_tweak_disable_event_analytics_summary),
                    String.valueOf(child.getSummary()));
            assertFalse(String.valueOf(child.getSummary()).contains("Hook:"));

            android.view.View childRow = android.view.LayoutInflater.from(activity)
                    .inflate(R.layout.zp_preference_row, null, false);
            androidx.preference.PreferenceViewHolder childHolder =
                    androidx.preference.PreferenceViewHolder.createInstanceForTests(childRow);
            getInstrumentation().runOnMainSync(() ->
                    ((ZpSwitchPreference) child).onBindViewHolder(childHolder));
            android.view.ViewGroup chips = childRow.findViewById(R.id.zp_row_chips);
            assertEquals(0, chips.getChildCount());
        } finally {
            UiSettings.setDisplayMode(activity, originalDisplayMode);
        }
    }

    public void testDashboardOffersAppLanguageAndDeveloperToolsOffersHookDetailToggle() {
        StatusActivity activity = getActivity();
        String originalDisplayMode = UiSettings.displayMode(activity);
        int originalPending = SettingsChanges.pendingCount(activity);
        try {
            StatusActivity.DashboardFragment fragment = new StatusActivity.DashboardFragment();
            open(activity, fragment);

            Preference language = fragment.findPreference(UiSettings.KEY_LANGUAGE);
            assertNull(fragment.findPreference(UiSettings.KEY_DISPLAY_MODE));
            assertTrue(language instanceof ZpListPreference);
            assertEquals(2, ((ZpListPreference) language).getEntries().length);
            assertEquals("Tiếng Việt", String.valueOf(
                    ((ZpListPreference) language).getEntries()[0]));
            assertEquals("English", String.valueOf(
                    ((ZpListPreference) language).getEntries()[1]));
            assertEquals(activity.getString(R.string.zp_language_title),
                    String.valueOf(language.getTitle()));
            assertEquals(UiSettings.language(activity), ((ZpListPreference) language).getValue());

            SectionActivity.SettingsFragment developer =
                    SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_DEVELOPER);
            open(activity, developer);
            Preference displayHookDetail =
                    developer.findPreference(UiSettings.KEY_DISPLAY_MODE);
            assertTrue(displayHookDetail instanceof ZpSwitchPreference);
            assertEquals("Display hook detail", String.valueOf(displayHookDetail.getTitle()));
            assertTrue(displayHookDetail.getOnPreferenceChangeListener().onPreferenceChange(
                    displayHookDetail, true));
            assertEquals(UiSettings.MODE_DETAILED, UiSettings.displayMode(activity));
            assertEquals(originalPending, SettingsChanges.pendingCount(activity));
        } finally {
            UiSettings.setDisplayMode(activity, originalDisplayMode);
        }
    }

    public void testVietnameseSettingsResourcesArePackaged() {
        android.content.res.Configuration configuration = new android.content.res.Configuration(
                getActivity().getResources().getConfiguration());
        configuration.setLocale(java.util.Locale.forLanguageTag("vi"));
        Context vietnamese = getActivity().createConfigurationContext(configuration);

        assertEquals("Display hook detail",
                vietnamese.getString(R.string.zp_display_hook_detail_title));
        assertEquals("Cá nhân", vietnamese.getString(R.string.zp_me_title));
        assertEquals("Disable event analytics",
                vietnamese.getString(R.string.zp_tweak_disable_event_analytics));
        assertEquals("Ngôn ngữ", vietnamese.getString(R.string.zp_language_title));
        assertEquals("Telemetry", vietnamese.getString(R.string.zp_telemetry_title));
        assertEquals("Self-check", vietnamese.getString(R.string.zp_self_check_page_title));
        assertEquals("Runtime environment",
                vietnamese.getString(R.string.zp_runtime_environment_title));
        assertEquals("Root access", vietnamese.getString(R.string.zp_root_access_title));
        assertEquals("Cần quyền root.", vietnamese.getString(R.string.zp_requirement_root));
        assertEquals("Có root: tự khởi động lại Zalo. Không có root: buộc dừng rồi mở lại.",
                vietnamese.getString(R.string.zp_restart_manual_fallback_summary));
    }

    public void testApplicationColdStartUsesSelectedLanguage() {
        Context application = getInstrumentation().getTargetContext().getApplicationContext();
        assertEquals(UiSettings.language(application),
                application.getResources().getConfiguration().getLocales().get(0).getLanguage());
    }

    public void testBackgroundContextUsesStoredLanguageAndEnglishFallback() {
        Context context = getActivity();
        SharedPreferences preferences = context.getSharedPreferences(
                UiSettings.PREFS_NAME, Context.MODE_PRIVATE);
        String original = preferences.getString(UiSettings.KEY_LANGUAGE, "");
        try {
            assertTrue(preferences.edit()
                    .putString(UiSettings.KEY_LANGUAGE, UiSettings.LANGUAGE_VIETNAMESE)
                    .commit());
            Context localized = UiSettings.localizedContext(context);
            assertEquals("Ngôn ngữ", localized.getString(R.string.zp_language_title));
            assertEquals("Telemetry", localized.getString(R.string.zp_telemetry_title));
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (original.isEmpty()) {
                editor.remove(UiSettings.KEY_LANGUAGE);
            } else {
                editor.putString(UiSettings.KEY_LANGUAGE, original);
            }
            assertTrue(editor.commit());
        }
    }

    public void testFooterUsesDedicatedStandardPreferencePattern() {
        ZpFooterPreference footer = PreferenceUi.footer(
                getActivity(), "Footer", "Supporting text");

        assertEquals(ZpFooterPreference.class, footer.getClass());
        assertEquals(androidx.preference.R.layout.preference_material,
                footer.getLayoutResource());
        assertFalse(footer.isSelectable());
        assertFalse(footer.isPersistent());
    }

    public void testMainSwitchReceivesContainedPressedState() {
        android.view.View row = android.view.LayoutInflater.from(getActivity())
                .inflate(R.layout.zp_preference_main_switch, null, false);
        android.view.View container = row.findViewById(R.id.zp_main_switch_container);

        assertNotNull(container);
        assertTrue(container.isDuplicateParentStateEnabled());
    }

    public void testSettingsScrollbarUsesThePhysicalScreenEdge() {
        StatusActivity activity = getActivity();
        StatusActivity.DashboardFragment fragment = new StatusActivity.DashboardFragment();
        open(activity, fragment);

        assertEquals(android.view.View.SCROLLBARS_OUTSIDE_OVERLAY,
                fragment.getListView().getScrollBarStyle());
    }

    public void testSavedSettingsBannerLeavesLastRowFullyReachableWithoutMovingOnHide() {
        StatusActivity activity = getActivity();
        SectionActivity.SettingsFragment fragment =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_ADS);
        open(activity, fragment);
        android.view.View banner = activity.findViewById(R.id.zp_apply_bar);
        androidx.recyclerview.widget.RecyclerView list = fragment.getListView();
        try {
            getInstrumentation().runOnMainSync(() -> banner.setVisibility(android.view.View.VISIBLE));
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> list.scrollBy(0, Integer.MAX_VALUE));
            getInstrumentation().waitForIdleSync();
            int[] edges = new int[3];
            getInstrumentation().runOnMainSync(() -> {
                int lastPosition = list.getAdapter().getItemCount() - 1;
                android.view.View last = list.findViewHolderForAdapterPosition(lastPosition).itemView;
                int[] rowLocation = new int[2];
                int[] bannerLocation = new int[2];
                last.getLocationOnScreen(rowLocation);
                banner.getLocationOnScreen(bannerLocation);
                edges[0] = rowLocation[1] + last.getHeight();
                edges[1] = bannerLocation[1];
                banner.setVisibility(android.view.View.GONE);
            });
            getInstrumentation().waitForIdleSync();
            getInstrumentation().runOnMainSync(() -> {
                android.view.View last = list.findViewHolderForAdapterPosition(
                        list.getAdapter().getItemCount() - 1).itemView;
                int[] location = new int[2];
                last.getLocationOnScreen(location);
                edges[2] = location[1] + last.getHeight();
            });
            assertTrue("The complete last row must scroll above the saved-settings banner",
                    edges[0] <= edges[1]);
            assertEquals("Hiding the banner must not move a row under the next tap", edges[0], edges[2]);
        } finally {
            getInstrumentation().runOnMainSync(() -> activity.refreshApplyBar(false));
        }
    }

    public void testCallRecordingDurationFormatting() {
        assertEquals("0:00", CallRecordingsActivity.formatDuration(0L));
        assertEquals("0:55", CallRecordingsActivity.formatDuration(55_962L));
        assertEquals("1:02", CallRecordingsActivity.formatDuration(62_000L));
        assertEquals("1:01:01", CallRecordingsActivity.formatDuration(3_661_000L));
    }

    private static void restorePreferences(
            SharedPreferences preferences, Map<String, ?> values) {
        SharedPreferences.Editor editor = preferences.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) {
                editor.putBoolean(entry.getKey(), (Boolean) value);
            } else if (value instanceof Integer) {
                editor.putInt(entry.getKey(), (Integer) value);
            } else if (value instanceof Long) {
                editor.putLong(entry.getKey(), (Long) value);
            } else if (value instanceof Float) {
                editor.putFloat(entry.getKey(), (Float) value);
            } else if (value instanceof String) {
                editor.putString(entry.getKey(), (String) value);
            } else if (value instanceof java.util.Set) {
                @SuppressWarnings("unchecked")
                java.util.Set<String> strings = (java.util.Set<String>) value;
                editor.putStringSet(entry.getKey(), new HashSet<>(strings));
            }
        }
        assertTrue(editor.commit());
    }

    private void open(StatusActivity activity, androidx.fragment.app.Fragment fragment) {
        getInstrumentation().runOnMainSync(() -> activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.zp_settings_content, fragment)
                .commitNowAllowingStateLoss());
        getInstrumentation().waitForIdleSync();
    }

    private androidx.fragment.app.Fragment current(StatusActivity activity) {
        return activity.getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
    }
}
