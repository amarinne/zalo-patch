package com.ez.zalopatch;

import android.content.Context;
import android.test.ActivityInstrumentationTestCase2;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;

import java.util.Set;

/**
 * Same-user instance selection is activity/saved state, never process-global. A clone is only
 * selectable while registered, always shows its package identity, and revocation returns the
 * writable target to the original without leaving a stale scoped page behind.
 */
public final class InstanceUiTest extends ActivityInstrumentationTestCase2<StatusActivity> {
    private static final String CLONE = "vn.example.instanceuitest";

    private final java.util.Map<android.content.SharedPreferences, java.util.Map<String, ?>>
            preserved = new java.util.LinkedHashMap<>();

    public InstanceUiTest() {
        super(StatusActivity.class);
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context module = getActivity();
        Context clone = HostPackages.scoped(module, CLONE);
        preserve(module.getSharedPreferences("trusted_zalo_packages", Context.MODE_PRIVATE));
        preserve(TweakStore.preferences(module));
        preserve(SettingsChanges.preferences(module));
        preserve(TweakStore.preferences(clone));
        preserve(SettingsChanges.preferences(clone));
    }

    @Override protected void tearDown() throws Exception {
        try {
            StatusActivity activity = getActivity();
            if (activity != null) {
                getInstrumentation().runOnMainSync(() -> {
                    activity.selectInstance(HostPackages.ZALO);
                    for (java.util.Map.Entry<android.content.SharedPreferences,
                            java.util.Map<String, ?>> entry : preserved.entrySet()) {
                        restore(entry.getKey(), entry.getValue());
                    }
                    activity.onRegistrationChanged();
                });
            }
        } finally {
            super.tearDown();
        }
    }

    private void preserve(android.content.SharedPreferences prefs) {
        preserved.put(prefs, new java.util.HashMap<>(prefs.getAll()));
    }

    private void restore(android.content.SharedPreferences prefs, java.util.Map<String, ?> values) {
        android.content.SharedPreferences.Editor editor = prefs.edit().clear();
        for (java.util.Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Set) {
                @SuppressWarnings("unchecked") Set<String> set = (Set<String>) value;
                editor.putStringSet(key, new java.util.HashSet<>(set));
            }
        }
        assertTrue(editor.commit());
    }

    public void testLatePermissionResultDoesNotFollowRestoredSelection() throws Exception {
        StatusActivity activity = getActivity();
        SectionActivity.SettingsFragment fragment =
                SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_CALLS);
        java.lang.reflect.Field generation = fragment.getClass()
                .getDeclaredField("recordingPermissionGeneration");
        java.lang.reflect.Field target = fragment.getClass()
                .getDeclaredField("recordingPermissionPackage");
        generation.setAccessible(true);
        target.setAccessible(true);
        getInstrumentation().runOnMainSync(() -> {
            activity.getSupportFragmentManager().beginTransaction()
                    .replace(R.id.zp_settings_content, fragment).commitNow();
            assertTrue(SettingsStore.putBoolean(activity,
                    Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS, false));
            try {
                generation.setLong(fragment, SettingsChanges.generation(activity));
                target.set(fragment, CLONE);
            } catch (IllegalAccessException error) {
                throw new AssertionError(error);
            }
            long previousGeneration = SettingsChanges.generation(activity);
            fragment.recordingPermissionResult(true);
            assertFalse(SettingsStore.getBoolean(activity, Tweaks.KEY_CALL_RECORDING_NOTIFICATIONS));
            assertEquals(previousGeneration, SettingsChanges.generation(activity));
        });
    }

    public void testSelectionUsesScopedContextAndRevocationReturnsToOriginal() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, CLONE));
            activity.onRegistrationChanged();
            activity.selectInstance(CLONE);
        });

        assertEquals(CLONE, activity.selectedInstancePackage());
        assertEquals(CLONE, activity.selectedPackageName());
        Context target = activity.settingsContext();
        assertEquals(CLONE, HostPackages.target(target));

        String key = Tweaks.KEY_HIDE_DISCOVERY_TAB;
        boolean originalValue = SettingsStore.getBoolean(activity, key);
        boolean cloneValue = SettingsStore.getBoolean(target, key);
        assertTrue(SettingsStore.putBoolean(target, key, !cloneValue));
        assertEquals("A clone edit must not rewrite the original",
                originalValue, SettingsStore.getBoolean(activity, key));
        assertTrue(SettingsStore.putBoolean(target, key, cloneValue));

        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, ""));
            activity.onRegistrationChanged();
        });
        assertEquals(HostPackages.ZALO, activity.selectedInstancePackage());
        assertEquals(HostPackages.ZALO, HostPackages.target(activity.settingsContext()));
    }

    public void testInstanceRowAppearsOnlyWithRegisteredClonesAndShowsAvailability() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, ""));
            activity.onRegistrationChanged();
            showDashboard(activity);
        });
        StatusActivity.DashboardFragment dashboard =
                (StatusActivity.DashboardFragment) current(activity);
        Preference hidden = dashboard.findPreference(StatusActivity.INTERNAL_INSTANCE);
        assertNotNull(hidden);
        assertFalse("No clone means no instance row", hidden.isVisible());

        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, CLONE));
            activity.onRegistrationChanged();
            activity.selectInstance(CLONE);
        });
        StatusActivity.DashboardFragment selected =
                (StatusActivity.DashboardFragment) current(activity);
        Preference row = selected.findPreference(StatusActivity.INTERNAL_INSTANCE);
        assertNotNull(row);
        assertTrue(row.isVisible());
        assertEquals(activity.getString(R.string.zp_instance_unavailable),
                String.valueOf(row.getSummary()));
        android.view.View bound = android.view.LayoutInflater.from(activity)
                .inflate(R.layout.zp_preference_row, null, false);
        row.onBindViewHolder(androidx.preference.PreferenceViewHolder.createInstanceForTests(bound));
        android.widget.TextView value = bound.findViewById(R.id.zp_row_value);
        assertNotNull("Package identity must stay visible", value);
        assertEquals(CLONE, value.getText().toString());
    }

    public void testCapturedDocumentTargetDoesNotFollowASelectionChange() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, CLONE));
            activity.onRegistrationChanged();
            activity.selectInstance(HostPackages.ZALO);
            activity.captureDocumentTarget();
            activity.selectInstance(CLONE);
            assertEquals(HostPackages.ZALO, activity.pendingDocumentPackage());

            activity.captureDocumentTarget();
            activity.selectInstance(HostPackages.ZALO);
            assertEquals(CLONE, activity.pendingDocumentPackage());
        });
    }

    public void testRevocationDropsTheScopedBackStack() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, CLONE));
            activity.onRegistrationChanged();
            activity.selectInstance(CLONE);
            activity.openPage(SectionActivity.SettingsFragment.forSection(Tweaks.SECTION_INBOX),
                    "Inbox");
        });
        getInstrumentation().waitForIdleSync();
        assertEquals(1, activity.getSupportFragmentManager().getBackStackEntryCount());
        assertTrue(current(activity) instanceof SectionActivity.SettingsFragment);

        getInstrumentation().runOnMainSync(() -> {
            assertTrue(HostPackages.save(activity, ""));
            activity.onRegistrationChanged();
        });
        getInstrumentation().waitForIdleSync();
        assertEquals(HostPackages.ZALO, activity.selectedInstancePackage());
        assertEquals(0, activity.getSupportFragmentManager().getBackStackEntryCount());
        assertTrue(current(activity) instanceof StatusActivity.DashboardFragment);
        assertEquals(HostPackages.ZALO, HostPackages.target(activity.settingsContext()));
    }

    private void showDashboard(StatusActivity activity) {
        activity.getSupportFragmentManager().beginTransaction()
                .replace(R.id.zp_settings_content, new StatusActivity.DashboardFragment())
                .commitNow();
    }

    private Fragment current(StatusActivity activity) {
        return activity.getSupportFragmentManager()
                .findFragmentById(R.id.zp_settings_content);
    }
}
