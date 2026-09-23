package com.ez.zalopatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One running Zalo app instance, keyed by Android user id.
 *
 * <p>Cloned/dual-app Zalo copies (Xiaomi Dual Apps / XSpace, Samsung Dual Messenger, work
 * profiles) all keep the package name {@code com.zing.zalo} but run under a different Android
 * user: the Linux uid encodes it ({@code u0_a266} is user 0, {@code u999_a266} is user 999).
 * Processes alone do not identify a copy — one instance may own several ({@code :push},
 * {@code :qav}, …) — so an instance is the set of Zalo processes owned by one user id.
 *
 * <p>Everything here is dependency-free string parsing so JVM tests can cover it; the actual
 * {@code su} execution lives in {@link ZaloRestart}.
 */
final class ZaloInstance {
    static final String PACKAGE = "com.zing.zalo";

    /** Separates the {@code ps} block from the {@code pm list users} block in one probe call. */
    static final String USERS_MARKER = "---ZP_USERS---";

    private static final Pattern ANDROID_USER = Pattern.compile("^u(\\d+)_.*");
    private static final Pattern USER_INFO =
            Pattern.compile("UserInfo\\{(\\d+):([^:]*):[^}]*\\}");

    final int userId;
    /** Profile label from {@code pm list users}; null when the user was not listed. */
    final String label;
    /** Pids of this instance's Zalo processes, ascending. Never empty. */
    final List<Integer> pids;
    /** True when this instance holds the lowest observed oom score (likely visible). */
    final boolean foreground;

    ZaloInstance(int userId, String label, List<Integer> pids, boolean foreground) {
        this.userId = userId;
        this.label = label;
        this.pids = Collections.unmodifiableList(new ArrayList<>(pids));
        this.foreground = foreground;
    }

    /** Lowest pid, the instance's main process. */
    int mainPid() {
        return pids.get(0);
    }

    /** A selector is only worth showing when more than one instance runs. */
    static boolean needsSelection(List<ZaloInstance> instances) {
        return instances != null && instances.size() > 1;
    }

    /** userIds in instance order, for restart-all. */
    static List<Integer> userIds(List<ZaloInstance> instances) {
        List<Integer> ids = new ArrayList<>();
        if (instances != null) {
            for (ZaloInstance instance : instances) ids.add(instance.userId);
        }
        return ids;
    }

    /**
     * Groups Zalo pids from {@code ps -A -o PID,USER,NAME} output by Android user id.
     * Stops at {@link #USERS_MARKER} when the users block follows in the same capture.
     */
    static Map<Integer, List<Integer>> parsePs(String output) {
        Map<Integer, List<Integer>> grouped = new LinkedHashMap<>();
        if (output == null) return grouped;
        for (String line : output.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || USERS_MARKER.equals(trimmed)) break;
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 3) continue;
            String name = fields[fields.length - 1];
            if (!PACKAGE.equals(name) && !name.startsWith(PACKAGE + ":")) continue;
            int pid;
            try {
                pid = Integer.parseInt(fields[0]);
            } catch (NumberFormatException ignored) {
                continue; // Header line ("PID USER NAME").
            }
            int userId = userIdOf(fields[1]);
            if (userId < 0) continue;
            List<Integer> pids = grouped.get(userId);
            if (pids == null) {
                pids = new ArrayList<>();
                grouped.put(userId, pids);
            }
            pids.add(pid);
        }
        for (List<Integer> pids : grouped.values()) Collections.sort(pids);
        return grouped;
    }

    /** Android user id encoded in a {@code ps} USER field ({@code u999_a266}, or a raw uid). */
    static int userIdOf(String user) {
        if (user == null) return -1;
        Matcher matcher = ANDROID_USER.matcher(user);
        if (matcher.matches()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        try {
            long uid = Long.parseLong(user);
            if (uid >= 0L) return (int) (uid / 100_000L);
        } catch (NumberFormatException ignored) {
        }
        return -1;
    }

    /** Maps user id to profile label from {@code pm list users} output. */
    static Map<Integer, String> parseUsers(String output) {
        Map<Integer, String> labels = new LinkedHashMap<>();
        if (output == null) return labels;
        Matcher matcher = USER_INFO.matcher(output);
        while (matcher.find()) {
            try {
                int id = Integer.parseInt(matcher.group(1));
                String name = matcher.group(2).trim();
                if (!name.isEmpty()) labels.put(id, name);
            } catch (NumberFormatException ignored) {
            }
        }
        return labels;
    }

    /** Maps pid to oom_score_adj from {@code echo $pid $(cat /proc/$pid/oom_score_adj)} lines. */
    static Map<Integer, Integer> parseOomScores(String output) {
        Map<Integer, Integer> scores = new LinkedHashMap<>();
        if (output == null) return scores;
        for (String line : output.split("\\r?\\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 2) continue;
            try {
                scores.put(Integer.parseInt(fields[0]), Integer.parseInt(fields[1]));
            } catch (NumberFormatException ignored) {
            }
        }
        return scores;
    }

    /**
     * Combines the three probe captures. Only the main (lowest) pid per instance feeds the
     * foreground comparison; the unique lowest score wins, ties and missing scores mark none.
     */
    static List<ZaloInstance> combine(Map<Integer, List<Integer>> grouped,
                                      Map<Integer, String> labels,
                                      Map<Integer, Integer> oomScores) {
        List<ZaloInstance> instances = new ArrayList<>();
        if (grouped == null || grouped.isEmpty()) return instances;
        Integer bestUser = null;
        int bestScore = Integer.MAX_VALUE;
        boolean tied = false;
        for (Map.Entry<Integer, List<Integer>> entry : grouped.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
            int mainPid = Collections.min(entry.getValue());
            Integer score = oomScores == null ? null : oomScores.get(mainPid);
            if (score == null) continue;
            if (bestUser == null || score < bestScore) {
                bestUser = entry.getKey();
                bestScore = score;
                tied = false;
            } else if (score == bestScore) {
                tied = true;
            }
        }
        List<Integer> sortedUsers = new ArrayList<>(grouped.keySet());
        Collections.sort(sortedUsers);
        for (int userId : sortedUsers) {
            List<Integer> pids = grouped.get(userId);
            if (pids == null || pids.isEmpty()) continue;
            List<Integer> sorted = new ArrayList<>(pids);
            Collections.sort(sorted);
            String label = labels == null ? null : labels.get(userId);
            instances.add(new ZaloInstance(userId, label, sorted,
                    !tied && bestUser != null && bestUser == userId));
        }
        return instances;
    }

    /** Per-user restart: resolve that user's launcher, stop and start only that user. */
    static String commandForUser(int userId) {
        String user = "--user " + userId + " ";
        return "launcher=$(cmd package resolve-activity " + user + "--brief "
                + "-a android.intent.action.MAIN "
                + "-c android.intent.category.LAUNCHER " + PACKAGE + " | tail -n 1) "
                + "&& [ \"${launcher#" + PACKAGE + "/}\" != \"$launcher\" ] "
                + "&& am force-stop " + user + PACKAGE + " "
                + "&& am start " + user + "-n \"$launcher\"";
    }

    /** Restart every listed user; one user's failure does not block the rest. */
    static String commandForAll(List<Integer> userIds) {
        StringBuilder command = new StringBuilder("failed=0");
        for (int userId : userIds) {
            command.append("; (").append(commandForUser(userId)).append(") || failed=1");
        }
        return command.append("; exit $failed").toString();
    }

    /** Single probe round: process list plus profile labels, split on {@link #USERS_MARKER}. */
    static String probeCommand() {
        return "ps -A -o PID,USER,NAME; echo " + USERS_MARKER + "; pm list users";
    }

    /** Second round over the main pids found in the first: {@code <pid> <oom_score_adj>} lines. */
    static String oomCommand(List<Integer> mainPids) {
        StringBuilder command = new StringBuilder();
        for (int pid : mainPids) {
            if (command.length() > 0) command.append("; ");
            command.append("echo -n \"").append(pid).append(" \"; cat /proc/")
                    .append(pid).append("/oom_score_adj 2>/dev/null || echo dead");
        }
        return command.toString();
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "ZaloInstance{user=%d label=%s pids=%s foreground=%s}",
                userId, label, pids, foreground);
    }
}
