package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.lang.reflect.Method;

import org.junit.Test;

public final class TelemetryFeatureTest {
    private static final class RoomDaoShape {
        public void eventWrite(Object entity) { }
        public int sessionWrite(Object entity) { return 0; }
        public long eventInsert(Object entity) { return 0L; }
        public void batchWrite(ArrayList<?> entities) { }
        public ArrayList<?> readRows(long limit) { return null; }
        public void twoArgs(Object first, Object second) { }
    }

    @Test
    public void currentRoomDaoWriteShapesAreSuppressible() throws Exception {
        assertTrue(isWrite("eventWrite", Object.class));
        assertTrue(isWrite("sessionWrite", Object.class));
        assertTrue(isWrite("eventInsert", Object.class));
    }

    @Test
    public void batchReadsAndNonWriteShapesAreExcluded() throws Exception {
        assertFalse(isWrite("batchWrite", ArrayList.class));
        assertFalse(isWrite("readRows", long.class));
        assertFalse(isWrite("twoArgs", Object.class, Object.class));
    }

    private static boolean isWrite(String name, Class<?>... parameters) throws Exception {
        Method method = RoomDaoShape.class.getDeclaredMethod(name, parameters);
        return TelemetryDaoShape.isWriteMethod(method);
    }
}
