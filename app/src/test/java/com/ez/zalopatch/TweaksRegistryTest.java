package com.ez.zalopatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
