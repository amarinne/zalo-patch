package com.ez.zalopatch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Copies a recording into the recovery namespace before allowing source deletion. */
final class CallRecordingTransfer {
    private final Executor executor;
    private final Set<String> queued;
    private final DirectorySync directorySync;

    interface DirectorySync {
        void sync(File directory) throws IOException;
    }

    CallRecordingTransfer(Executor executor, Set<String> queued, DirectorySync directorySync) {
        this.executor = executor;
        this.queued = queued;
        this.directorySync = directorySync;
    }

    /** Owns source, including on duplicate requests and executor rejection. */
    void enqueue(InputStream source, File processing, byte[] metadata,
            Predicate<File> validWave, Consumer<Boolean> completion) {
        String key = processing.getAbsolutePath();
        if (!queued.add(key)) {
            closeQuietly(source);
            complete(completion, false);
            return;
        }
        try {
            executor.execute(() -> {
                boolean copied = false;
                try (InputStream input = source) {
                    copyToRecovery(input, processing, metadata, validWave);
                    copied = true;
                } catch (Exception ignored) {
                    // Only a complete, synced WAV and sidecar authorize source deletion.
                } finally {
                    queued.remove(key);
                }
                complete(completion, copied);
            });
        } catch (RuntimeException rejected) {
            queued.remove(key);
            closeQuietly(source);
            complete(completion, false);
        }
    }

    private void copyToRecovery(InputStream input, File processing, byte[] metadata,
            Predicate<File> validWave) throws IOException {
        // An existing recovery file can be the only remaining copy. Never truncate it,
        // and do not assume that a file left by an older importer is complete.
        if (processing.exists()) {
            throw new IOException("Recording already awaits recovery");
        }
        File staging = new File(processing.getPath() + ".importing");
        File sidecar = new File(processing.getPath() + ".json");
        File stagedMetadata = new File(sidecar.getPath() + ".importing");
        try {
            try (FileOutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Recording import interrupted");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (!validWave.test(staging)) {
                throw new IOException("Transferred WAV is incomplete");
            }
            try (FileOutputStream output = new FileOutputStream(stagedMetadata)) {
                output.write(metadata);
                output.getFD().sync();
            }
            // Recovery only scans .processing.wav. Install metadata first so that every
            // newly visible recovery WAV already has its complete metadata.
            if (!stagedMetadata.renameTo(sidecar) || !staging.renameTo(processing)) {
                throw new IOException("Could not commit recording import");
            }
            directorySync.sync(processing.getParentFile());
        } finally {
            staging.delete();
            stagedMetadata.delete();
        }
    }

    private static void complete(Consumer<Boolean> completion, boolean accepted) {
        try {
            completion.accept(accepted);
        } catch (RuntimeException ignored) {
            // A dead callback cannot roll back the recovery copy or change the result.
        }
    }

    private static void closeQuietly(InputStream source) {
        try {
            source.close();
        } catch (IOException ignored) {
        }
    }
}
