package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TweaksRegistryTest {
    @Test
    public void integratedCandidatesRemainDefaultOffAndPortable() {
        for (String key : new String[]{Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS,
                Tweaks.KEY_VIEW_STORIES_ANONYMOUSLY, Tweaks.KEY_PREFER_ORIGINAL_PHOTO,
                Tweaks.KEY_CUSTOM_REACTION,
                Tweaks.KEY_DISABLE_ADTIMA_OFFLINE_TRACKING, Tweaks.KEY_HIDE_STORY_ADS,
                Tweaks.KEY_HIDE_VIDEO_ADS}) {
            assertTrue(key, Tweaks.isImplemented(key));
            assertFalse(key, Tweaks.defaultEnabled(key));
            Settings.Setting<?> setting = Settings.find(key);
            assertNotNull(key, setting);
            assertFalse(key, (Boolean) setting.defaultValue);
            assertTrue(key, SettingsBackup.portable(setting));
        }
    }

    @Test
    public void itemKeysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (Tweaks.Item item : Tweaks.ITEMS) {
            assertTrue("Duplicate tweak item: " + item.key, keys.add(item.key));
        }
    }

    @Test
    public void everyItemBelongsToExactlyOneGroup() throws IllegalAccessException {
        Map<String, String> owners = new HashMap<>();
        for (String section : sections()) {
            for (Tweaks.Group group : Tweaks.groups(section)) {
                for (String key : group.keys) {
                    assertNull("Duplicate grouped tweak: " + key,
                            owners.put(key, section));
                }
            }
        }

        for (Tweaks.Item item : Tweaks.ITEMS) {
            assertEquals("Tweak is not grouped in its declared section: " + item.key,
                    item.section, owners.get(item.key));
        }
    }

    @Test
    public void everyGroupHasAResolvableTitleResource() throws IllegalAccessException {
        Set<Integer> stringResources = new HashSet<>();
        for (Field field : R.string.class.getDeclaredFields()) {
            stringResources.add(field.getInt(null));
        }
        for (String section : sections()) {
            for (Tweaks.Group group : Tweaks.groups(section)) {
                assertTrue("Missing group title resource in section " + section,
                        stringResources.contains(group.titleRes));
            }
        }
    }

    @Test
    public void sponsoredPlacementToggleIsDefaultOffAndPortable() {
        String key = Tweaks.KEY_DISABLE_SPONSORED_PLACEMENTS;
        org.junit.Assert.assertTrue(Tweaks.isImplemented(key));
        org.junit.Assert.assertFalse(Tweaks.defaultEnabled(key));
        boolean foundItem = false;
        for (Tweaks.Item item : Tweaks.ITEMS) {
            if (key.equals(item.key)) {
                foundItem = true;
                org.junit.Assert.assertEquals(Tweaks.Requirement.NONE, item.requirement);
            }
        }
        org.junit.Assert.assertTrue(foundItem);
        Settings.Setting<?> setting = Settings.find(key);
        org.junit.Assert.assertNotNull(setting);
        org.junit.Assert.assertEquals(Settings.Type.BOOLEAN, setting.type);
        org.junit.Assert.assertFalse((Boolean) setting.defaultValue);
        org.junit.Assert.assertTrue(SettingsBackup.portable(setting));
    }

    @Test
    public void storyAdSettingUsesPortableDefaultOffRegistry() {
        Tweaks.Item item = null;
        for (Tweaks.Item candidate : Tweaks.ITEMS) {
            if (Tweaks.KEY_HIDE_STORY_ADS.equals(candidate.key)) item = candidate;
        }
        assertNotNull(item);
        assertEquals(Tweaks.SECTION_ADS, item.section);
        assertFalse(item.defaultEnabled);
        assertTrue(item.implemented);
        assertEquals(Tweaks.Requirement.NONE, item.requirement);
        assertNotNull(Settings.find(Tweaks.KEY_HIDE_STORY_ADS));
    }

    private static List<String> sections() throws IllegalAccessException {
        List<String> sections = new ArrayList<>();
        for (Field field : Tweaks.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getName().startsWith("SECTION_")
                    && field.getType() == String.class) {
                sections.add((String) field.get(null));
            }
        }
        return sections;
    }
}
