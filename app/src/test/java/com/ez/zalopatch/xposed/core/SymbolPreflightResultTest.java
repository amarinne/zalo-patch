package com.ez.zalopatch.xposed.core;

import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;

public final class SymbolPreflightResultTest {
    @Test
    public void emptyResultResolvesNothing() {
        assertEquals(0, new SymbolPreflight.Result().resolved());
    }

    @Test
    public void eachResolvedFamilyPreventsAnEmptyResult() throws Exception {
        for (Field family : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (family.getType() != boolean.class) continue;
            SymbolPreflight.Result result = new SymbolPreflight.Result();
            family.setBoolean(result, true);
            assertEquals(family.getName(), 1, result.resolved());
        }
    }

    @Test
    public void completeResultResolvesEveryDeclaredFamily() throws Exception {
        SymbolPreflight.Result result = new SymbolPreflight.Result();
        for (Field family : SymbolPreflight.Result.class.getDeclaredFields()) {
            if (family.getType() == boolean.class) family.setBoolean(result, true);
        }
        assertEquals(result.total(), result.resolved());
    }
}
