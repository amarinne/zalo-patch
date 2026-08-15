package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class UiSettingsTest {
    @Test
    public void emptyLocaleDefaultsToEnglish() {
        assertEquals(UiSettings.LANGUAGE_ENGLISH, UiSettings.languageForTags(""));
    }

    @Test
    public void onlyEnglishAndVietnameseAreSelected() {
        assertEquals(UiSettings.LANGUAGE_VIETNAMESE, UiSettings.languageForTags("vi-VN"));
        assertEquals(UiSettings.LANGUAGE_ENGLISH, UiSettings.languageForTags("en-US"));
        assertEquals(UiSettings.LANGUAGE_ENGLISH, UiSettings.languageForTags("fr-FR"));
    }

    @Test
    public void initializationPreservesExplicitLanguageAndDefaultsToEnglish() {
        assertEquals(UiSettings.LANGUAGE_ENGLISH,
                UiSettings.languageForInitialization("", ""));
        assertEquals(UiSettings.LANGUAGE_VIETNAMESE,
                UiSettings.languageForInitialization(UiSettings.LANGUAGE_VIETNAMESE, ""));
        assertEquals(UiSettings.LANGUAGE_VIETNAMESE,
                UiSettings.languageForInitialization("", "vi-VN"));
        assertEquals(UiSettings.LANGUAGE_VIETNAMESE,
                UiSettings.languageForInitialization(UiSettings.LANGUAGE_VIETNAMESE, "en-US"));
    }
}
