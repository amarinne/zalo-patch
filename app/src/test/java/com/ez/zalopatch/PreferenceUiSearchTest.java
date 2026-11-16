package com.ez.zalopatch;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PreferenceUiSearchTest {
    @Test
    public void blankQueryMatchesEverything() {
        assertTrue(PreferenceUi.matchesSearch("", "Any tweak", "Any summary"));
        assertTrue(PreferenceUi.matchesSearch("   ", "Any tweak"));
    }

    @Test
    public void foldsCaseAndVietnameseDiacritics() {
        assertTrue(PreferenceUi.matchesSearch("khoanh khac", "Xem khoảnh khắc ẩn danh"));
        assertTrue(PreferenceUi.matchesSearch("KHOANH", "Xem khoảnh khắc ẩn danh"));
        assertTrue(PreferenceUi.matchesSearch("dang nhap", "Đang nhập"));
    }

    @Test
    public void requiresEveryQueryTermToMatchSomeField() {
        assertTrue(PreferenceUi.matchesSearch("call audio", "Record call audio automatically",
                "Calls"));
        assertTrue(PreferenceUi.matchesSearch("audio call", "Record call audio automatically"));
        assertFalse(PreferenceUi.matchesSearch("call video", "Record call audio automatically"));
    }

    @Test
    public void matchesSectionTitleAsWellAsRowCopy() {
        assertTrue(PreferenceUi.matchesSearch("tabs", "Hide Discovery tab", "", "Tabs and startup"));
        assertTrue(PreferenceUi.matchesSearch("hide discover", "Hide Discovery tab", "Tabs"));
        assertFalse(PreferenceUi.matchesSearch("hide discover", "Hide Timeline tab", "Tabs"));
    }
}
