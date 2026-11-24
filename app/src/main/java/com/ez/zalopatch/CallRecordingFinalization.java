package com.ez.zalopatch;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

/** Crash-safe state for a retained WAV. The caller holds its file lock while changing state. */
record CallRecordingFinalization(
        int attempts, boolean manual, String mediaUri, long encodedSize, Outcome outcome) {
    static final int MAX_ATTEMPTS = 3;
    static final long STALL_TIMEOUT_MS = 60_000L;
    static final Comparator<File> NEWEST_FIRST = Comparator.comparingLong(File::lastModified)
            .thenComparing(File::getName).reversed();
    private static final int MAX_REPORT_ATTEMPTS = 2;
    private static final CallRecordingFinalization NEW =
            new CallRecordingFinalization(0, false, "", 0L, Outcome.PENDING);

    enum Outcome { PENDING, SAVED, FAILED }
    enum Next { CONVERT, VERIFY_PUBLICATION, EXHAUSTED, REPORT, DONE }

    Next next() {
        if (outcome != Outcome.PENDING) {
            return attempts < MAX_REPORT_ATTEMPTS ? Next.REPORT : Next.DONE;
        }
        if (attempts < MAX_ATTEMPTS) return Next.CONVERT;
        if (attempts == MAX_ATTEMPTS && !mediaUri.isEmpty()) return Next.VERIFY_PUBLICATION;
        return Next.EXHAUSTED;
    }

    CallRecordingFinalization start(File wav) throws IOException {
        CallRecordingFinalization started = new CallRecordingFinalization(
                attempts + 1, manual, mediaUri, encodedSize, outcome);
        started.write(wav);
        return started;
    }

    void publishing(File wav, String uri, long size) throws IOException {
        new CallRecordingFinalization(attempts, manual, uri, size, outcome).write(wav);
    }

    void resetForRetry(File wav) throws IOException {
        new CallRecordingFinalization(0, true, mediaUri, encodedSize, Outcome.PENDING).write(wav);
    }

    void complete(File wav, Outcome result) throws IOException {
        new CallRecordingFinalization(0, manual, mediaUri, encodedSize, result).write(wav);
    }

    void finishReporting(File wav) throws IOException {
        new CallRecordingFinalization(
                MAX_REPORT_ATTEMPTS, manual, mediaUri, encodedSize, outcome).write(wav);
    }

    static CallRecordingFinalization read(File wav) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file(wav).toPath()))) {
            return new CallRecordingFinalization(
                    input.readInt(), input.readBoolean(), input.readUTF(), input.readLong(),
                    Outcome.valueOf(input.readUTF()));
        } catch (NoSuchFileException missing) {
            return NEW;
        } catch (IllegalArgumentException corrupt) {
            throw new IOException("Invalid recording finalization state", corrupt);
        }
    }

    static File file(File wav) {
        return new File(wav.getPath() + ".finalization");
    }

    void write(File wav) throws IOException {
        File state = file(wav);
        File staging = new File(state.getPath() + ".writing");
        try {
            try (FileOutputStream file = new FileOutputStream(staging);
                 DataOutputStream output = new DataOutputStream(file)) {
                output.writeInt(attempts);
                output.writeBoolean(manual);
                output.writeUTF(mediaUri);
                output.writeLong(encodedSize);
                output.writeUTF(outcome.name());
                output.flush();
                file.getFD().sync();
            }
            Files.move(staging.toPath(), state.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            staging.delete();
        }
    }

    static final class Watchdog {
        private final AtomicLong deadlineMs = new AtomicLong();

        Watchdog(long nowMs) {
            progress(nowMs);
        }

        void progress(long nowMs) {
            deadlineMs.set(nowMs + STALL_TIMEOUT_MS);
        }

        boolean expired(long nowMs) {
            return nowMs >= deadlineMs.get();
        }
    }
}
