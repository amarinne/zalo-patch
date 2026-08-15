package com.ez.zalopatch;

import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/** Executes fixed diagnostic root commands with bounded prefix/tail capture. */
final class DiagnosticRootProcessRunner {
    Result run(String command, long timeoutMs) {
        Process process = null;
        Thread reader = null;
        RollingOutput capture = new RollingOutput();
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            Process running = process;
            reader = new Thread(() -> {
                try (InputStream input = running.getInputStream()) {
                    capture.read(input);
                } catch (IOException ignored) {
                    capture.readFailed = true;
                }
            }, "ZaloPatchDiagnosticCommand");
            reader.setDaemon(true);
            reader.start();
            boolean finished = waitFor(running, timeoutMs);
            if (!finished) {
                running.destroy();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    running.destroyForcibly();
                }
            }
            reader.join(READER_JOIN_MS);
            return new Result(
                    finished && !capture.readFailed ? running.exitValue() : -1,
                    capture.asString(), !finished, capture.truncated());
        } catch (Exception ignored) {
            return new Result(-1, "", false, false);
        } finally {
            if (process != null) process.destroy();
            if (reader != null) reader.interrupt();
        }
    }

    private static boolean waitFor(Process process, long timeoutMs) throws InterruptedException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
                long remainingNs = deadline - System.nanoTime();
                if (remainingNs <= 0L) return false;
                long sleepMs = Math.min(50L,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs)));
                Thread.sleep(sleepMs);
            }
        }
    }

    static final class Result {
        final int exitCode;
        final String output;
        final boolean timedOut;
        final boolean outputTruncated;

        Result(int exitCode, String output, boolean timedOut, boolean outputTruncated) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
            this.outputTruncated = outputTruncated;
        }

        boolean successful() {
            return !timedOut && exitCode == 0;
        }
    }

    private static final class RollingOutput {
        private final ArrayList<Byte> prefix = new ArrayList<>(PREFIX_BYTES);
        private final byte[] tail = new byte[TAIL_BYTES];
        private int tailCount;
        private int tailIndex;
        private long totalBytes;
        volatile boolean readFailed;

        void read(InputStream input) throws IOException {
            byte[] buffer = new byte[8 * 1024];
            while (!Thread.currentThread().isInterrupted()) {
                int count = input.read(buffer);
                if (count < 0) break;
                for (int index = 0; index < count; index++) append(buffer[index]);
            }
        }

        private void append(byte value) {
            totalBytes++;
            if (prefix.size() < PREFIX_BYTES) {
                prefix.add(value);
                return;
            }
            tail[tailIndex] = value;
            tailIndex = (tailIndex + 1) % TAIL_BYTES;
            if (tailCount < TAIL_BYTES) tailCount++;
        }

        boolean truncated() {
            return totalBytes > PREFIX_BYTES + TAIL_BYTES;
        }

        String asString() {
            byte[] prefixBytes = new byte[prefix.size()];
            for (int index = 0; index < prefix.size(); index++) {
                prefixBytes[index] = prefix.get(index);
            }
            if (tailCount == 0) return new String(prefixBytes, StandardCharsets.UTF_8);
            byte[] tailBytes = new byte[tailCount];
            int start = tailCount == TAIL_BYTES ? tailIndex : 0;
            for (int index = 0; index < tailBytes.length; index++) {
                tailBytes[index] = tail[(start + index) % TAIL_BYTES];
            }
            String separator = truncated()
                    ? "\n--- COMMAND OUTPUT MIDDLE OMITTED ---\n" : "";
            return new String(prefixBytes, StandardCharsets.UTF_8) + separator
                    + new String(tailBytes, StandardCharsets.UTF_8);
        }
    }

    private static final int PREFIX_BYTES = 128 * 1024;
    private static final int TAIL_BYTES = 1024 * 1024;
    private static final long READER_JOIN_MS = 1_000L;
}
