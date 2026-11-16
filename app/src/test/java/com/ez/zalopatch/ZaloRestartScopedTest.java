package com.ez.zalopatch;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class ZaloRestartScopedTest {
    private static final String CLONE = "vn.example.zalo2";

    @Test
    public void capturedTargetDoesNotFollowLaterSelectionOrRecreation() {
        ZaloRestart.SelectedTarget captured = new ZaloRestart.SelectedTarget(CLONE, 10);
        String laterUiSelection = HostPackages.ZALO;

        assertEquals(CLONE, captured.packageName);
        assertEquals(10, captured.androidUserId);
        assertTrue(captured.valid(Collections.singleton(CLONE), 10, null));
        assertFalse(captured.valid(Collections.singleton(CLONE), 10, laterUiSelection));
        assertEquals(captured, new ZaloRestart.SelectedTarget(
                captured.packageName, captured.androidUserId));
    }

    @Test
    public void targetPolicyRejectsInvalidUserPackageRegistrationAndContext() {
        Set<String> trusted = Collections.singleton(CLONE);
        assertTrue(new ZaloRestart.SelectedTarget(HostPackages.ZALO, 0)
                .valid(Collections.emptySet(), 0, null));
        assertTrue(new ZaloRestart.SelectedTarget(CLONE, 0).valid(trusted, 0, CLONE));

        assertFalse(new ZaloRestart.SelectedTarget(CLONE, 0)
                .valid(Collections.emptySet(), 0, null)); // unregistered or revoked
        assertFalse(new ZaloRestart.SelectedTarget(CLONE, 0)
                .valid(Collections.singleton(HostPackages.ZALO), 0, null));
        assertFalse(new ZaloRestart.SelectedTarget(CLONE, 10)
                .valid(trusted, 0, null)); // captured user differs from module user
        assertFalse(new ZaloRestart.SelectedTarget(CLONE, 0)
                .valid(trusted, 10, null));
        assertFalse(new ZaloRestart.SelectedTarget(CLONE, 0)
                .valid(trusted, 0, HostPackages.ZALO)); // scoped context mismatch
        assertFalse(new ZaloRestart.SelectedTarget("com.zing.zalo.clone", 0)
                .valid(Collections.emptySet(), 0, null)); // prefix is not admission

        for (String invalid : new String[]{
                "com.zing.zalo;id", "com.zing.zalo clone", "com.zing.zalo/..",
                "com.ez.zalopatch", "android", "invalid", "com.zing.zalo.*"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ZaloRestart.SelectedTarget(invalid, 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new ZaloRestart.SelectedTarget(CLONE, -1));
    }

    @Test
    public void platformUidDerivesTheExplicitOwnUser() {
        assertEquals(0, ZaloInstance.androidUser(10266));
        assertEquals(10, ZaloInstance.androidUser(1010266));
        assertEquals(999, ZaloInstance.androidUser(99910266));
        assertEquals(-1, ZaloInstance.androidUser(-1));
    }

    @Test
    public void scopedCommandUsesOnlyTheExactPackageAndExplicitUser() {
        String original = ZaloInstance.commandForPackage(HostPackages.ZALO, 7);
        assertTrue(original.contains("resolve-activity --user 7 --brief"));
        assertTrue(original.contains("-a android.intent.action.MAIN"));
        assertTrue(original.contains("-c android.intent.category.LAUNCHER com.zing.zalo |"));
        assertTrue(original.contains("[ \"${launcher#com.zing.zalo/}\" != \"$launcher\" ]"));
        assertTrue(original.contains("am force-stop --user 7 com.zing.zalo "));
        assertTrue(original.contains("am start --user 7 -n \"$launcher\""));

        String clone = ZaloInstance.commandForPackage(CLONE, 10);
        assertTrue(clone.contains("resolve-activity --user 10 --brief"));
        assertTrue(clone.contains("LAUNCHER " + CLONE + " |"));
        assertTrue(clone.contains("am force-stop --user 10 " + CLONE + " "));
        assertTrue(clone.contains("am start --user 10 -n \"$launcher\""));
        assertFalse(clone.contains("force-stop --user 10 com.zing.zalo "));
        assertFalse(clone.contains("LAUNCHER com.zing.zalo |"));

        assertThrows(IllegalArgumentException.class,
                () -> ZaloInstance.commandForPackage(CLONE, -1));
        for (String invalid : new String[]{
                "com.zing.zalo;id", "com.zing.zalo clone", "com.zing.zalo/..",
                "com.ez.zalopatch", "android", "invalid", "com.zing.zalo.*"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ZaloInstance.commandForPackage(invalid, 0));
        }
    }

    @Test
    public void exactPackageProcessMatchingNeverAdmitsPrefixes() {
        String output = "PID USER         NAME\n"
                + "10 u0_a266      " + CLONE + "\n"
                + "11 u0_a266      " + CLONE + ":push\n"
                + "12 u0_a266      com.zing.zalo\n"
                + "13 u0_a266      com.zing.zalo2\n"
                + "14 u0_a266      com.zing.zalo:push\n"
                + "15 u999_a266    " + CLONE + "\n";

        Map<Integer, List<Integer>> original = ZaloInstance.parsePsForPackage(
                output, HostPackages.ZALO);
        assertEquals(Arrays.asList(12, 14), original.get(0));
        assertEquals(1, original.size());

        Map<Integer, List<Integer>> clone = ZaloInstance.parsePsForPackage(output, CLONE);
        assertEquals(Arrays.asList(10, 11), clone.get(0));
        assertEquals(Collections.singletonList(15), clone.get(999));
        assertEquals(2, clone.size());
    }

    @Test
    public void stoppedOrUnprobedCloneStillBuildsTheCloneCommand() {
        assertTrue(ZaloInstance.parsePsForPackage("PID USER NAME\n", CLONE).isEmpty());
        assertTrue(ZaloInstance.parsePsForPackage(null, CLONE).isEmpty());
        String command = ZaloInstance.commandForPackage(CLONE, 0);
        assertTrue(command.contains("am force-stop --user 0 " + CLONE + " "));
        assertTrue(command.contains("am start --user 0 -n \"$launcher\""));
        assertFalse(command.contains("force-stop --user 0 com.zing.zalo "));
    }

    @Test
    public void shellCommandStartsOnlyTheVerifiedExactComponent() throws Exception {
        Assume.assumeTrue(Files.isExecutable(Paths.get("/bin/sh")));
        String clone = ZaloInstance.commandForPackage(CLONE, 7);

        ShellResult started = runShell(clone, CLONE + "/.MainActivity");
        assertEquals(0, started.exit);
        assertEquals("force-stop --user 7 " + CLONE + "\n"
                + "start --user 7 -n " + CLONE + "/.MainActivity\n", started.log);

        assertRejected(clone, HostPackages.ZALO + "/.MainActivity");

        ShellResult resolveFailure = runShell(clone, "");
        assertNotEquals(0, resolveFailure.exit);
        assertEquals("", resolveFailure.log);

        assertRejected(clone, CLONE + "/");
    }

    private static void assertRejected(String command, String launcher) throws Exception {
        ShellResult result = runShell(command, launcher);
        assertNotEquals(0, result.exit);
        assertEquals("", result.log);
    }

    @Test
    public void legacyCommandsRemainByteForByte() {
        String user999 = "launcher=$(cmd package resolve-activity --user 999 --brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER com.zing.zalo | tail -n 1) "
                + "&& [ \"${launcher#com.zing.zalo/}\" != \"$launcher\" ] "
                + "&& am force-stop --user 999 com.zing.zalo "
                + "&& am start --user 999 -n \"$launcher\"";
        assertEquals(user999, ZaloInstance.commandForUser(999));
        assertEquals("failed=0; (" + ZaloInstance.commandForUser(0) + ") || failed=1; ("
                + user999 + ") || failed=1; exit $failed",
                ZaloInstance.commandForAll(Arrays.asList(0, 999)));
        assertEquals("launcher=$(cmd package resolve-activity --brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER com.zing.zalo | tail -n 1) "
                + "&& [ \"${launcher#com.zing.zalo/}\" != \"$launcher\" ] "
                + "&& am force-stop com.zing.zalo "
                + "&& am start -n \"$launcher\"", ZaloRestart.legacyCommand());
    }

    private static ShellResult runShell(String command, String launcher) throws Exception {
        Path bin = Files.createTempDirectory("zp-restart-");
        try {
            Path log = bin.resolve("am.log");
            writeExecutable(bin.resolve("cmd"),
                    "#!/bin/sh\nprintf '%s\\n' \"$ZP_LAUNCHER\"\n");
            writeExecutable(bin.resolve("am"),
                    "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$ZP_AM_LOG\"\n");
            ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
            Map<String, String> environment = builder.environment();
            String path = environment.get("PATH");
            environment.put("PATH", bin + File.pathSeparator + (path == null ? "" : path));
            environment.put("ZP_LAUNCHER", launcher);
            environment.put("ZP_AM_LOG", log.toString());
            Process process = builder.start();
            int exit = process.waitFor();
            String captured = Files.exists(log) ? Files.readString(log) : "";
            return new ShellResult(exit, captured);
        } finally {
            deleteTree(bin);
        }
    }

    private static void writeExecutable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        if (!path.toFile().setExecutable(true)) {
            throw new IOException("Cannot make test helper executable: " + path);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static final class ShellResult {
        final int exit;
        final String log;

        ShellResult(int exit, String log) {
            this.exit = exit;
            this.log = log;
        }
    }
}
