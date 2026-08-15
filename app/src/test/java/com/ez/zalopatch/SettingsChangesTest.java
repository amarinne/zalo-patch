package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;

import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class SettingsChangesTest {
    @Test
    public void pendingCountCountsDistinctKeys() {
        TestContext context = new TestContext();

        SettingsChanges.markChanged(context, "inbox.chips");
        SettingsChanges.markChanged(context, "calls.auto_record");

        assertEquals(2, SettingsChanges.pendingCount(context));
    }

    @Test
    public void togglingAnImportedKeyIsNotCountedTwice() {
        TestContext context = new TestContext();

        // A settings import records each key it applied.
        SettingsChanges.markChanged(context, "inbox.chips");
        SettingsChanges.markChanged(context, "calls.auto_record");
        // The user then toggles one of those same keys by hand.
        SettingsChanges.markChanged(context, "inbox.chips");

        assertEquals(2, SettingsChanges.pendingCount(context));
    }

    @Test
    public void togglingAKeyOutsideAnImportIsCounted() {
        TestContext context = new TestContext();

        SettingsChanges.markChanged(context, "inbox.chips");
        SettingsChanges.markChanged(context, "calls.auto_record");
        SettingsChanges.markChanged(context, "telemetry.disable_ad_id");

        assertEquals(3, SettingsChanges.pendingCount(context));
    }

    @Test
    public void clearAtCurrentGenerationResetsPendingCount() {
        TestContext context = new TestContext();

        SettingsChanges.markChanged(context, "inbox.chips");
        SettingsChanges.clearIfGeneration(context, SettingsChanges.generation(context));

        assertEquals(0, SettingsChanges.pendingCount(context));
    }

    @Test
    public void clearKeepsChangesMadeAfterTheRestartStarted() {
        TestContext context = new TestContext();

        SettingsChanges.markChanged(context, "inbox.chips");
        long restartGeneration = SettingsChanges.generation(context);
        // The user toggles something else while the restart is still in flight.
        SettingsChanges.markChanged(context, "calls.auto_record");

        SettingsChanges.clearIfGeneration(context, restartGeneration);

        assertEquals(2, SettingsChanges.pendingCount(context));
    }

    private static final class TestContext extends ContextWrapper {
        private final SharedPreferences preferences = newPreferences();

        TestContext() {
            super(null);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    @SuppressWarnings("unchecked")
    private static SharedPreferences newPreferences() {
        Map<String, Object> values = new HashMap<>();
        InvocationHandler preferencesHandler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getStringSet".equals(name)) {
                Set<String> stored = (Set<String>) values.get(args[0]);
                return stored == null ? args[1] : new HashSet<>(stored);
            }
            if ("getInt".equals(name) || "getLong".equals(name)) {
                Object stored = values.get(args[0]);
                return stored == null ? args[1] : stored;
            }
            if ("edit".equals(name)) {
                return newEditor(values);
            }
            if ("getAll".equals(name)) {
                return new HashMap<>(values);
            }
            if ("contains".equals(name)) {
                return values.containsKey(args[0]);
            }
            if ("registerOnSharedPreferenceChangeListener".equals(name)
                    || "unregisterOnSharedPreferenceChangeListener".equals(name)) {
                return null;
            }
            throw new UnsupportedOperationException(name);
        };
        return (SharedPreferences) Proxy.newProxyInstance(
                SettingsChangesTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, preferencesHandler);
    }

    private static SharedPreferences.Editor newEditor(Map<String, Object> values) {
        InvocationHandler editorHandler = new InvocationHandler() {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();

            @Override
            @SuppressWarnings("unchecked")
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("putStringSet".equals(name) || "putInt".equals(name)
                        || "putLong".equals(name)) {
                    updates.put((String) args[0],
                            args[1] instanceof Set ? new HashSet<>((Set<String>) args[1]) : args[1]);
                    return proxy;
                }
                if ("remove".equals(name)) {
                    removals.add((String) args[0]);
                    return proxy;
                }
                if ("apply".equals(name) || "commit".equals(name)) {
                    for (String key : removals) {
                        values.remove(key);
                    }
                    values.putAll(updates);
                    return "commit".equals(name);
                }
                throw new UnsupportedOperationException(name);
            }
        };
        return (SharedPreferences.Editor) Proxy.newProxyInstance(
                SettingsChangesTest.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.Editor.class}, editorHandler);
    }
}
