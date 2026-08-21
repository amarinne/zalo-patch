package com.ez.zalopatch.xposed.features;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class StatusPrivacyAckFilterTest {
    @Test
    public void filterSeenDropsOnlySeenEntries() {
        Entry delivered = new Entry(2);
        Entry seen = new Entry(3);
        Entry other = new Entry(1);

        StatusPrivacyAckFilter.Result result = StatusPrivacyAckFilter.filterSeen(
                Arrays.asList(delivered, seen, other), 3, entry -> ((Entry) entry).type);

        assertEquals(1, result.dropped);
        assertEquals(2, result.kept.size());
        assertSame(delivered, result.kept.get(0));
        assertSame(other, result.kept.get(1));
    }

    @Test
    public void filterSeenKeepsUnknownShapes() {
        Object unknown = new Object();

        StatusPrivacyAckFilter.Result result = StatusPrivacyAckFilter.filterSeen(
                Arrays.asList(new Entry(3), unknown), 3, entry -> ((Entry) entry).type);

        assertEquals(1, result.dropped);
        assertEquals(1, result.kept.size());
        assertSame(unknown, result.kept.get(0));
    }

    @Test
    public void directAckUsesExplicitSeenFlag() {
        assertTrue(StatusPrivacyAckFilter.shouldBlockDirectAck(true));
        assertFalse(StatusPrivacyAckFilter.shouldBlockDirectAck(false));
    }

    private static final class Entry {
        final int type;

        Entry(int type) {
            this.type = type;
        }
    }
}
