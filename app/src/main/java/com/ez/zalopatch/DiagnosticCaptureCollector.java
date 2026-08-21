package com.ez.zalopatch;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Collects only fixed, filtered diagnostic sources after explicit user action. */
final class DiagnosticCaptureCollector {
    private final DiagnosticRootProcessRunner runner;

    DiagnosticCaptureCollector(DiagnosticRootProcessRunner runner) {
        this.runner = runner;
    }

    CapturedData collect(long startedAtWallMs) {
        return collect(startedAtWallMs, RootAccess.probe(runner));
    }

    CapturedData collect(long startedAtWallMs, RootAccess.State rootState) {
        String rootStatus = rootState.reportValue();
        if (rootState != RootAccess.State.GRANTED) {
            return new CapturedData("metadata_only_root_denied", rootStatus, "", "", "",
                    java.util.Collections.singletonList("root_access"),
                    java.util.Collections.emptyMap());
        }

        String timestamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date(startedAtWallMs));
        LinkedHashMap<String, String> commands = new LinkedHashMap<>();
        commands.put("logs", "logcat -d -b main -b system -v threadtime -t 4000 "
                + "-s ZaloPatch:V '*:S'");
        commands.put("zalo_processes", PROCESS_COMMAND);
        commands.put("framework", FRAMEWORK_EVIDENCE_COMMAND);
        commands.put("crash", "logcat -d -b crash -v threadtime -T '" + timestamp + "'");
        commands.put("lsposed", LSPOSED_COMMAND);

        LinkedHashMap<String, DiagnosticRootProcessRunner.Result> results = new LinkedHashMap<>();
        for (Map.Entry<String, String> command : commands.entrySet()) {
            results.put(command.getKey(), runner.run(
                    command.getValue(), DiagnosticReportContract.COMMAND_TIMEOUT_MS));
        }
        ArrayList<String> failures = new ArrayList<>();
        for (Map.Entry<String, DiagnosticRootProcessRunner.Result> result : results.entrySet()) {
            if (!result.getValue().successful()) failures.add(result.getKey());
        }

        StringBuilder rawLogs = new StringBuilder();
        DiagnosticRootProcessRunner.Result processResult = results.get("zalo_processes");
        String processes = filterProcessLines(processResult.output);
        rawLogs.append("zalo_processes=").append(readProcessStatus(processResult)).append('\n');
        if (!processes.isEmpty()) {
            rawLogs.append("Zalo process snapshot:\n").append(processes).append('\n');
        }
        String framework = DiagnosticReportContract.sanitizeLines(
                results.get("framework").output);
        if (!framework.trim().isEmpty()) {
            rawLogs.append("Xposed framework evidence:\n").append(framework).append('\n');
        }
        rawLogs.append(DiagnosticReportContract.sanitizeLines(
                filterSafeModuleLines(results.get("logs").output)));

        String rawCrash = filterAllowedCrashBlocks(results.get("crash").output);
        String lsposedOutput = results.get("lsposed").output;
        String rawLsposed = "lsposed_log=" + readLsposedStatus(lsposedOutput) + '\n'
                + DiagnosticReportContract.sanitizeLines(filterModuleLines(lsposedOutput));

        DiagnosticReportContract.BoundedText logs = DiagnosticReportContract.truncateLines(
                rawLogs.toString(), DiagnosticReportContract.LOGCAT_BYTES);
        DiagnosticReportContract.BoundedText crash = DiagnosticReportContract.truncateLines(
                rawCrash, DiagnosticReportContract.CRASH_BYTES);
        DiagnosticReportContract.BoundedText lsposed = DiagnosticReportContract.truncateLines(
                rawLsposed, DiagnosticReportContract.LSPOSED_BYTES);
        LinkedHashMap<String, Boolean> truncation = new LinkedHashMap<>();
        truncation.put("diagnosticEventsAndLogs",
                logs.truncated || results.get("logs").outputTruncated);
        truncation.put("crashExcerpt",
                crash.truncated || results.get("crash").outputTruncated);
        truncation.put("lsposedModuleLines",
                lsposed.truncated || results.get("lsposed").outputTruncated);
        return new CapturedData(failures.isEmpty() ? "captured" : "partial_capture",
                "granted", logs.text, crash.text, lsposed.text, failures, truncation);
    }

    static String checkRootAccess(DiagnosticRootProcessRunner runner) {
        return RootAccess.probe(runner).reportValue();
    }

    static String filterModuleLines(String output) {
        StringBuilder filtered = new StringBuilder();
        for (String line : lines(output)) {
            if ((line.contains("ZaloPatch") || line.contains("com.ez.zalopatch"))
                    && safeModuleLine(line)) {
                appendLine(filtered, line);
            }
        }
        return filtered.toString();
    }

    static String filterSafeModuleLines(String output) {
        StringBuilder filtered = new StringBuilder();
        for (String line : lines(output)) {
            if (safeModuleLine(line)) appendLine(filtered, line);
        }
        return filtered.toString();
    }

    private static boolean safeModuleLine(String line) {
        String lower = line.toLowerCase(Locale.US);
        if (line.contains("[RuntimeDiscovery]")) {
            return line.contains("[RuntimeDiscovery] CANDIDATE ")
                    || line.contains("[RuntimeDiscovery] VIEW ")
                    || line.contains("[RuntimeDiscovery]   fields ->")
                    || line.contains("[RuntimeDiscovery]   methods ->");
        }
        return !lower.contains(" row uid=")
                && !lower.contains("tap row ->")
                && !lower.contains("tabme item snapshot")
                && !lower.contains(" title=")
                && !lower.contains(",title=")
                && !lower.contains(" desc=")
                && !lower.contains(",desc=");
    }

    static String filterProcessLines(String output) {
        StringBuilder filtered = new StringBuilder();
        for (String line : lines(output)) {
            String trimmed = line.trim();
            if (PROCESS_LINE.matcher(trimmed).find()) appendLine(filtered, trimmed);
        }
        return filtered.toString();
    }

    static String readProcessStatus(DiagnosticRootProcessRunner.Result result) {
        if (!result.successful() || result.output.contains("zalo_processes_unavailable")) {
            return "unavailable";
        }
        return filterProcessLines(result.output).isEmpty() ? "empty" : "matched";
    }

    static String readLsposedStatus(String output) {
        if (output.contains("lsposed_log=absent")) return "absent";
        if (!output.contains("lsposed_log=present")) return "unknown";
        return filterModuleLines(output).isEmpty() ? "empty" : "matched";
    }

    static String filterAllowedCrashBlocks(String output) {
        if (output == null || output.trim().isEmpty()) return "";
        ArrayList<List<String>> blocks = new ArrayList<>();
        ArrayList<String> current = new ArrayList<>();
        for (String line : lines(output)) {
            if (isCrashBoundary(line) && !current.isEmpty()) {
                blocks.add(current);
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) blocks.add(current);
        StringBuilder filtered = new StringBuilder();
        for (List<String> block : blocks) {
            boolean allowed = false;
            for (String line : block) {
                if (ALLOWED_CRASH_PROCESS.matcher(line).find()) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) continue;
            for (String line : block) {
                String sanitized = sanitizeCrashLine(line);
                if (sanitized != null) appendLine(filtered, sanitized);
            }
        }
        return filtered.toString();
    }

    private static String sanitizeCrashLine(String line) {
        String redacted = DiagnosticReportContract.redactLine(line);
        String trimmed = redacted.trim();
        int separator = redacted.indexOf(": ");
        String content = (separator >= 0 ? redacted.substring(separator + 2) : redacted).trim();
        if (trimmed.contains("FATAL EXCEPTION") || trimmed.startsWith("Process: ")
                || trimmed.startsWith("Cmdline: ") || trimmed.startsWith("pid: ")
                || EXCEPTION_CLASS_LINE.matcher(trimmed).find()
                || content.contains("FATAL EXCEPTION") || content.startsWith("Process: ")
                || content.startsWith("Cmdline: ") || content.startsWith("pid: ")
                || content.startsWith("signal ") || "backtrace:".equals(content)
                || content.startsWith("#") || content.startsWith("at ")
                || content.startsWith("Caused by: ") || content.startsWith("Suppressed: ")
                || EXCEPTION_CLASS_LINE.matcher(content).find()) {
            return redacted;
        }
        return null;
    }

    private static boolean isCrashBoundary(String line) {
        return line.contains("FATAL EXCEPTION") || line.startsWith("*** *** ***")
                || line.contains("Fatal signal ");
    }

    private static String[] lines(String value) {
        return value == null || value.isEmpty() ? new String[0] : value.split("\\r?\\n");
    }

    private static void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) builder.append('\n');
        builder.append(line);
    }

    static final class CapturedData {
        final String outcome;
        final String rootAccessStatus;
        final String logs;
        final String crashExcerpt;
        final String lsposedLines;
        final List<String> commandFailures;
        final Map<String, Boolean> truncationFlags;

        CapturedData(String outcome, String rootAccessStatus, String logs, String crashExcerpt,
                     String lsposedLines, List<String> commandFailures,
                     Map<String, Boolean> truncationFlags) {
            this.outcome = outcome;
            this.rootAccessStatus = rootAccessStatus;
            this.logs = logs;
            this.crashExcerpt = crashExcerpt;
            this.lsposedLines = lsposedLines;
            this.commandFailures = commandFailures;
            this.truncationFlags = truncationFlags;
        }
    }

    private static final String LSPOSED_COMMAND =
            "f=$(ls -1t /data/adb/lspd/log/modules_*.log "
                    + "/data/adb/lspd/log/verbose/modules_*.log 2>/dev/null | head -n 1); "
                    + "if [ -z \"$f\" ]; then echo lsposed_log=absent; exit 0; fi; "
                    + "echo lsposed_log=present; tail -c 524288 \"$f\" | "
                    + "grep -E 'ZaloPatch|com\\.ez\\.zalopatch' || true";
    private static final String PROCESS_COMMAND =
            "ps -A -o USER,UID,PID,ETIME,NAME >/dev/null 2>&1 || "
                    + "{ echo zalo_processes_unavailable; exit 0; }; "
                    + "ps -A -o USER,UID,PID,ETIME,NAME 2>/dev/null | "
                    + "grep -E 'com\\.zing\\.zalo|com\\.ez\\.zalopatch' || true";
    private static final String FRAMEWORK_EVIDENCE_COMMAND =
            "if [ -d /data/adb/lspd ]; then echo lspd_dir=present; "
                    + "else echo lspd_dir=absent; fi; "
                    + "if [ -d /data/adb/lspd/log ]; then echo lspd_log_dir=present; "
                    + "else echo lspd_log_dir=absent; fi; "
                    + "found_root=0; "
                    + "if [ -d /data/adb/ksu ]; then echo root_solution=ksu; found_root=1; fi; "
                    + "if [ -d /data/adb/ap ]; then echo root_solution=ap; found_root=1; fi; "
                    + "if [ -d /data/adb/magisk ]; then echo root_solution=magisk; found_root=1; fi; "
                    + "if [ \"$found_root\" -eq 0 ]; then echo root_solution=none_detected; fi; "
                    + "echo manager_packages=listed; pm list packages 2>/dev/null | "
                    + "grep -Ei 'package:(org\\.lsposed|io\\.github\\.lsposed|"
                    + "org\\.meowcat\\.edxposed|de\\.robv\\.android\\.xposed|"
                    + "org\\.lsposed\\.lspatch|com\\.android\\.shell\\.lsposed)' || true";
    private static final Pattern ALLOWED_CRASH_PROCESS = Pattern.compile(
            "(?:Process:|Cmdline:|>>>)\\s*(?:com\\.ez\\.zalopatch|com\\.zing\\.zalo)"
                    + "(?=[:,\\s<]|$)");
    private static final Pattern EXCEPTION_CLASS_LINE = Pattern.compile(
            "^(?:(?:[A-Za-z_][A-Za-z0-9_$]*\\.)*[A-Za-z0-9_$]+"
                    + "(?:Exception|Error))");
    private static final Pattern PROCESS_LINE = Pattern.compile(
            "\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+"
                    + "(?:com\\.zing\\.zalo|com\\.ez\\.zalopatch)(?::[A-Za-z0-9_.-]+)?"
                    + "(?:\\s|$)");
}
