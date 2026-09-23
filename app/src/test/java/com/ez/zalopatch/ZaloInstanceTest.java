package com.ez.zalopatch;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ZaloInstanceTest {
    private static final String PS =
            "PID USER         NAME\n"
                    + "  101 root         init\n"
                    + "20351 u0_a266      com.zing.zalo\n"
                    + "20390 u0_a266      com.zing.zalo:push\n"
                    + "31007 u999_a266    com.zing.zalo\n"
                    + "24838 u0_a410      com.ez.zalopatch\n"
                    + "31100 u10_a50      com.zing.zalo:qav\n";

    private static final String USERS =
            "Users:\n"
                    + "\tUserInfo{0:x:4c13} running\n"
                    + "\tUserInfo{999:XSpace:801010} running\n"
                    + "\tUserInfo{10:Work:1010} running\n";

    @Test
    public void psGroupsByAndroidUserAndIgnoresOtherPackages() {
        Map<Integer, List<Integer>> grouped = ZaloInstance.parsePs(PS);
        assertEquals(Arrays.asList(20351, 20390), grouped.get(0));
        assertEquals(Collections.singletonList(31007), grouped.get(999));
        assertEquals(Collections.singletonList(31100), grouped.get(10));
        assertEquals(3, grouped.size());
    }

    @Test
    public void psStopsAtUsersMarker() {
        Map<Integer, List<Integer>> grouped = ZaloInstance.parsePs(
                PS + ZaloInstance.USERS_MARKER + "\n" + USERS);
        assertEquals(3, grouped.size());
    }

    @Test
    public void userIdHandlesNamedAndNumericOwners() {
        assertEquals(0, ZaloInstance.userIdOf("u0_a266"));
        assertEquals(999, ZaloInstance.userIdOf("u999_a266"));
        assertEquals(10, ZaloInstance.userIdOf("u10_a50"));
        assertEquals(0, ZaloInstance.userIdOf("10266"));
        assertEquals(1, ZaloInstance.userIdOf("110266"));
        assertEquals(-1, ZaloInstance.userIdOf("root"));
        assertEquals(-1, ZaloInstance.userIdOf(null));
    }

    @Test
    public void usersMapIdsToProfileLabels() {
        Map<Integer, String> labels = ZaloInstance.parseUsers(USERS);
        assertEquals("x", labels.get(0));
        assertEquals("XSpace", labels.get(999));
        assertEquals("Work", labels.get(10));
    }

    @Test
    public void combineMarksUniqueLowestOomAsForeground() {
        Map<Integer, List<Integer>> grouped = ZaloInstance.parsePs(PS);
        Map<Integer, String> labels = ZaloInstance.parseUsers(USERS);
        Map<Integer, Integer> oom = ZaloInstance.parseOomScores(
                "20351 100\n31007 900\n31100 900\n");
        List<ZaloInstance> instances = ZaloInstance.combine(grouped, labels, oom);
        assertEquals(3, instances.size());
        assertEquals(0, instances.get(0).userId);
        assertTrue(instances.get(0).foreground);
        assertEquals("x", instances.get(0).label);
        assertEquals(20351, instances.get(0).mainPid());
        assertFalse(instances.get(1).foreground);
        assertFalse(instances.get(2).foreground);
        assertTrue(ZaloInstance.needsSelection(instances));
        assertEquals(Arrays.asList(0, 10, 999), ZaloInstance.userIds(instances));
    }

    @Test
    public void combineMarksNoneOnOomTie() {
        Map<Integer, List<Integer>> grouped = ZaloInstance.parsePs(PS);
        Map<Integer, Integer> oom = ZaloInstance.parseOomScores(
                "20351 900\n31007 900\n31100 900\n");
        List<ZaloInstance> instances = ZaloInstance.combine(grouped, null, oom);
        for (ZaloInstance instance : instances) assertFalse(instance.foreground);
        assertNull(instances.get(0).label);
    }

    @Test
    public void singleInstanceNeedsNoSelection() {
        assertFalse(ZaloInstance.needsSelection(Collections.emptyList()));
        assertFalse(ZaloInstance.needsSelection(null));
        ZaloInstance only = new ZaloInstance(0, null,
                Collections.singletonList(20351), true);
        assertFalse(ZaloInstance.needsSelection(Collections.singletonList(only)));
    }

    @Test
    public void perUserCommandScopesBothStopAndStart() {
        String command = ZaloInstance.commandForUser(999);
        assertTrue(command.contains("resolve-activity --user 999 --brief"));
        assertTrue(command.contains("am force-stop --user 999 com.zing.zalo"));
        assertTrue(command.contains("am start --user 999 -n \"$launcher\""));
    }

    @Test
    public void allCommandCoversEveryUserWithoutEarlyExit() {
        String command = ZaloInstance.commandForAll(Arrays.asList(0, 999));
        assertTrue(command.contains("--user 0"));
        assertTrue(command.contains("--user 999"));
        assertTrue(command.endsWith("; exit $failed"));
    }

    @Test
    public void legacyCommandKeepsUnqualifiedBehavior() {
        String command = ZaloRestart.legacyCommand();
        assertTrue(command.contains("am force-stop com.zing.zalo "));
        assertTrue(command.contains("am start -n \"$launcher\""));
        assertFalse(command.contains("--user"));
    }
}
