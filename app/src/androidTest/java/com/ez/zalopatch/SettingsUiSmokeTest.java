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

    public void testDependencyPagesOpenInSingleHost() {
        StatusActivity activity = getActivity();
        open(activity, SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_INBOX));
        assertTrue(current(activity) instanceof SectionActivity.SettingsFragment);

        open(activity, SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_CALLS));
        assertTrue(current(activity) instanceof SectionActivity.SettingsFragment);

        SectionActivity.SettingsFragment developer =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_DEVELOPER);
        open(activity, developer);
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

    public void testMainRestartRemainsVisibleWithPendingChanges() {
        StatusActivity activity = getActivity();
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        try {
            SettingsChanges.markChanged(activity, "test.pending");
            StatusActivity.DashboardFragment fragment =
                    new StatusActivity.DashboardFragment();
            open(activity, fragment);
            Preference restart = fragment.findPreference(StatusActivity.INTERNAL_RESTART);

            assertNotNull(restart);
            assertTrue(restart.isVisible());
        } finally {
            restorePreferences(ui, originalUi);
        }
    }

    public void testTelemetryUsesMainSwitchPatternAndTracksChildren() {
        StatusActivity activity = getActivity();
        String originalDisplayMode = UiSettings.displayMode(activity);
        SharedPreferences tweaks = activity.getSharedPreferences(
                Tweaks.PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences ui = SettingsChanges.preferences(activity);
        Map<String, Boolean> originalTelemetry = new HashMap<>();
        Map<String, ?> originalUi = new HashMap<>(ui.getAll());
        for (String key : Tweaks.TELEMETRY_KEYS) {
            originalTelemetry.put(key, tweaks.getBoolean(key, Tweaks.defaultEnabled(key)));
        }

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
                assertTrue(renderedSummary.contains(
                        "com.zing.zalo.analytics.db.AnalyticsRoomDatabase_Impl#z()"));
                assertTrue(child.getSummary() instanceof android.text.Spanned);
                android.text.Spanned styledSummary = (android.text.Spanned) child.getSummary();
                int symbolStart = renderedSummary.indexOf("#z()");
                android.text.style.ForegroundColorSpan[] symbolColors = styledSummary.getSpans(
                        symbolStart, symbolStart + 4,
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
            SharedPreferences.Editor restoreTweaks = tweaks.edit();
            for (Map.Entry<String, Boolean> entry : originalTelemetry.entrySet()) {
                restoreTweaks.putBoolean(entry.getKey(), entry.getValue());
            }
            assertTrue(restoreTweaks.commit());
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
