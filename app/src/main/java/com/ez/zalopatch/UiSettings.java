package com.ez.zalopatch;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import java.util.Locale;

/** Module-process presentation preferences. These never propagate into the hooked Zalo process. */
final class UiSettings {
    static final String KEY_DISPLAY_MODE = "module_ui.display_mode";
    static final String KEY_LANGUAGE = "module_ui.language";
    static final String MODE_SIMPLIFIED = "simplified";
    static final String MODE_DETAILED = "detailed";
    static final String LANGUAGE_ENGLISH = "en";
    static final String LANGUAGE_VIETNAMESE = "vi";

    /**
     * Module-process only: presentation preferences for the settings app itself. Deliberately not
     * the hook-readable Tweaks file and not the SettingsChanges file; see {@link Tweaks#PREFS_NAME}.
     */
    static final String PREFS_NAME = "module_ui";

    private UiSettings() {
    }

    static String displayMode(Context context) {
        String value = preferences(context).getString(KEY_DISPLAY_MODE, MODE_SIMPLIFIED);
        return MODE_DETAILED.equals(value) ? MODE_DETAILED : MODE_SIMPLIFIED;
    }

    static void setDisplayMode(Context context, String value) {
        preferences(context).edit().putString(KEY_DISPLAY_MODE,
                MODE_DETAILED.equals(value) ? MODE_DETAILED : MODE_SIMPLIFIED).apply();
    }

    static boolean isDetailed(Context context) {
        return MODE_DETAILED.equals(displayMode(context));
    }

    static String language(Context context) {
        String stored = preferences(context).getString(KEY_LANGUAGE, "");
        if (LANGUAGE_ENGLISH.equals(stored) || LANGUAGE_VIETNAMESE.equals(stored)) {
            return stored;
        }
        String tags;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            tags = manager == null ? "" : manager.getApplicationLocales().toLanguageTags();
        } else {
            tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
        }
        return languageForTags(tags);
    }

    static String languageForTags(String tags) {
        if (tags.isEmpty()) {
            return LANGUAGE_ENGLISH;
        }
        String language = Locale.forLanguageTag(tags.split(",", 2)[0]).getLanguage();
        if (LANGUAGE_VIETNAMESE.equalsIgnoreCase(language)) {
            return LANGUAGE_VIETNAMESE;
        }
        return LANGUAGE_ENGLISH;
    }

    static void setLanguage(Context context, String value) {
        String tags;
        if (LANGUAGE_VIETNAMESE.equals(value)) {
            tags = LANGUAGE_VIETNAMESE;
        } else {
            tags = LANGUAGE_ENGLISH;
        }
        preferences(context).edit().putString(KEY_LANGUAGE, tags).commit();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager != null) {
                manager.setApplicationLocales(LocaleList.forLanguageTags(tags));
            }
            return;
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags));
    }

    static void ensureDefaultLanguage(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            String tags = AppCompatDelegate.getApplicationLocales().toLanguageTags();
            String stored = preferences(context).getString(KEY_LANGUAGE, "");
            String selected = languageForInitialization(stored, tags);
            preferences(context).edit().putString(KEY_LANGUAGE, selected).commit();
            if (tags.isEmpty() || !selected.equals(languageForTags(tags))) {
                AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(selected));
            }
            return;
        }
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        if (manager == null) {
            return;
        }
        String tags = manager.getApplicationLocales().toLanguageTags();
        String stored = preferences(context).getString(KEY_LANGUAGE, "");
        String selected = languageForInitialization(stored, tags);
        preferences(context).edit().putString(KEY_LANGUAGE, selected).commit();
        if (tags.isEmpty() || !selected.equals(languageForTags(tags))) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(selected));
        }
    }

    static Context localizedContext(Context context) {
        Configuration configuration = new Configuration(
                context.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(Locale.forLanguageTag(language(context))));
        return context.createConfigurationContext(configuration);
    }

    static String languageForInitialization(String stored, String activeTags) {
        if (LANGUAGE_VIETNAMESE.equals(stored) || LANGUAGE_ENGLISH.equals(stored)) {
            return stored;
        }
        return languageForTags(activeTags == null ? "" : activeTags);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
