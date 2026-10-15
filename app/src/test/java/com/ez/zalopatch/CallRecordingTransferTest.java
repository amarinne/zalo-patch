package com.ez.zalopatch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class CallRecordingTransferTest {
    private static final byte[] METADATA = ("{\"started_at\":1785150000000,"
            + "\"direction\":\"incoming\",\"display_name\":\"Fixture\",\"phone_number\":\"\"}")
            .getBytes(StandardCharsets.UTF_8);
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final Set<String> queued = Collections.synchronizedSet(new HashSet<>());
    private final List<Boolean> completions = new ArrayList<>();

    @Test public void queuedCopyDoesNotAuthorizeDeletionUntilBytesAndMetadataAreRecoverable()
            throws Exception {
        Fixture fixture = fixture();
        List<Runnable> tasks = new ArrayList<>();
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(tasks::add).enqueue(input, fixture.destination, METADATA, this::validWave, accepted -> {
            assertTrue(accepted);
            assertTrue(input.closed);
            assertRecovery(fixture);
            completions.add(accepted);
            assertTrue(fixture.source.delete());
        });
        assertTrue(completions.isEmpty());
        assertTrue(fixture.source.isFile());
        assertFalse(fixture.destination.exists());
        tasks.remove(0).run();
        assertEquals(Collections.singletonList(true), completions);
        assertFalse(fixture.source.exists());
        assertTrue(queued.isEmpty());
    }

    @Test public void partialReadFailureKeepsSourceAndAllowsRetry() throws Exception {
        Fixture fixture = fixture();
        TrackingInput input = new TrackingInput(fixture.source) {
            int reads;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (reads++ > 0) throw new IOException("Injected read failure");
                return super.read(bytes, offset, length);
            }
        };
        transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                this::validWave, completions::add);
        assertFailed(fixture, input);
        assertFalse(fixture.destination.exists());
        retry(fixture);
    }

    @Test public void outputFailureKeepsSource() throws Exception {
        Fixture fixture = fixture();
        Files.createDirectory(new File(fixture.destination + ".importing").toPath());
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                this::validWave, completions::add);
        assertFailed(fixture, input);
        assertFalse(fixture.destination.exists());
    }

    @Test public void metadataFailureDoesNotExposeRecoverableWavOrDeleteSource() throws Exception {
        Fixture fixture = fixture();
        Files.createDirectory(new File(fixture.destination + ".json.importing").toPath());
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                this::validWave, completions::add);
        assertFailed(fixture, input);
        assertFalse(fixture.destination.exists());
        retry(fixture);
    }

    @Test public void incompleteWaveDoesNotAuthorizeSourceDeletion() throws Exception {
        Fixture fixture = fixture();
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                file -> false, completions::add);
        assertFailed(fixture, input);
        assertFalse(fixture.destination.exists());
    }

    @Test public void directorySyncFailureKeepsBothSourceAndRecoveryCopy() throws Exception {
        Fixture fixture = fixture();
        TrackingInput input = new TrackingInput(fixture.source);
        new CallRecordingTransfer(Runnable::run, queued, directory -> {
            throw new IOException("Injected directory sync failure");
        }).enqueue(input, fixture.destination, METADATA, this::validWave, completions::add);
        assertFailed(fixture, input);
        assertRecovery(fixture);
    }

    @Test public void interruptedCopyKeepsSourceAndReleasesReservation() throws Exception {
        Fixture fixture = fixture();
        TrackingInput input = new TrackingInput(fixture.source);
        Thread.currentThread().interrupt();
        try {
            transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                    this::validWave, completions::add);
            assertFailed(fixture, input);
            assertFalse(fixture.destination.exists());
        } finally {
            Thread.interrupted();
        }
    }

    @Test public void executorRejectionClosesDescriptorAndAllowsRetry() throws Exception {
        Fixture fixture = fixture();
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(task -> { throw new RejectedExecutionException("Injected rejection"); })
                .enqueue(input, fixture.destination, METADATA, this::validWave, completions::add);
        assertFailed(fixture, input);
        assertFalse(fixture.destination.exists());
        retry(fixture);
    }

    @Test public void duplicateDuringCopyIsRejectedWithoutReleasingFirstReservation()
            throws Exception {
        Fixture fixture = fixture();
        List<Runnable> tasks = new ArrayList<>();
        CallRecordingTransfer transfer = transfer(tasks::add);
        transfer.enqueue(new TrackingInput(fixture.source), fixture.destination, METADATA,
                this::validWave, completions::add);
        TrackingInput duplicate = new TrackingInput(fixture.source);
        transfer.enqueue(duplicate, fixture.destination, METADATA, this::validWave, completions::add);
        assertEquals(Collections.singletonList(false), completions);
        assertTrue(duplicate.closed);
        assertTrue(queued.contains(fixture.destination.getAbsolutePath()));
        assertTrue(fixture.source.exists());
        assertEquals(1, tasks.size());
        tasks.remove(0).run();
        assertEquals(Arrays.asList(false, true), completions);
        assertRecovery(fixture);
        assertTrue(queued.isEmpty());
    }

    @Test public void existingRecoveryFileIsNeverTruncatedOrAssumedComplete() throws Exception {
        Fixture fixture = fixture();
        byte[] oldBytes = new byte[]{1, 2, 3};
        Files.write(fixture.destination.toPath(), oldBytes);
        Files.write(fixture.metadata.toPath(), new byte[]{4, 5});
        TrackingInput input = new TrackingInput(fixture.source);
        transfer(Runnable::run).enqueue(input, fixture.destination, METADATA,
                this::validWave, completions::add);
        assertFailed(fixture, input);
        assertArrayEquals(oldBytes, Files.readAllBytes(fixture.destination.toPath()));
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(fixture.metadata.toPath()));
    }

    @Test public void callbackFailureCannotRollBackCopyOrSendAnotherResult() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        transfer(Runnable::run).enqueue(new TrackingInput(fixture.source), fixture.destination,
                METADATA, this::validWave, accepted -> {
                    assertTrue(accepted);
                    calls.incrementAndGet();
                    throw new IllegalStateException("Sender disappeared");
                });
        assertEquals(1, calls.get());
        assertTrue(fixture.source.exists());
        assertRecovery(fixture);
        assertTrue(queued.isEmpty());
    }

    @Test public void processDeathDuringCopyKeepsSourceAndNextProcessCanRetry() throws Exception {
        Fixture fixture = fixture();
        crashWorker(fixture, "copy");
        assertTrue(fixture.source.exists());
        assertFalse(fixture.destination.exists());
        File partial = new File(fixture.destination + ".importing");
        assertTrue(partial.length() > 0);
        assertTrue(partial.length() < fixture.source.length());
        retry(fixture);
        assertFalse(partial.exists());
    }

    @Test public void processDeathBeforeAcknowledgementLeavesRecoverableBytesAndMetadata()
            throws Exception {
        Fixture fixture = fixture();
        crashWorker(fixture, "ack");
        assertTrue(fixture.source.exists());
        assertRecovery(fixture);
        TrackingInput replay = new TrackingInput(fixture.source);
        transfer(Runnable::run).enqueue(replay, fixture.destination, METADATA,
                this::validWave, completions::add);
        assertFailed(fixture, replay);
        assertRecovery(fixture);
    }

    private void crashWorker(Fixture fixture, String phase) throws Exception {
        String classpath = new File(CallRecordingTransferTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getPath() + File.pathSeparator
                + new File(CallRecordingTransfer.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getPath();
        Process child = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java")
                .getPath(), "-cp", classpath, CrashWorker.class.getName(),
                fixture.source.getPath(), fixture.destination.getPath(), phase)
                .redirectErrorStream(true).start();
        try {
            assertTrue("Fixture process did not exit", child.waitFor(10, TimeUnit.SECONDS));
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(output, 23, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    public static final class CrashWorker {
        public static void main(String[] args) throws Exception {
            InputStream input = new FilterInputStream(new FileInputStream(args[0])) {
                int reads;
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    if ("copy".equals(args[2]) && reads++ > 0) Runtime.getRuntime().halt(23);
                    return super.read(bytes, offset, length);
                }
            };
            new CallRecordingTransfer(Runnable::run, new HashSet<>(), directory -> {
                try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
                    channel.force(true);
                }
            }).enqueue(input, new File(args[1]), Files.readAllBytes(new File(args[0] + ".json").toPath()),
                    file -> true, accepted -> {
                        if (accepted && "ack".equals(args[2])) Runtime.getRuntime().halt(23);
                    });
            throw new AssertionError("Crash point was not reached");
        }
    }

    private CallRecordingTransfer transfer(Executor executor) {
        return new CallRecordingTransfer(executor, queued, directory -> {
            try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
                channel.force(true);
            }
        });
    }

    private boolean validWave(File file) {
        try {
            return Arrays.equals(wave(), Files.readAllBytes(file.toPath()));
        } catch (IOException error) {
            return false;
        }
    }

    private void retry(Fixture fixture) throws Exception {
        completions.clear();
        transfer(Runnable::run).enqueue(new TrackingInput(fixture.source), fixture.destination,
                METADATA, this::validWave, completions::add);
        assertEquals(Collections.singletonList(true), completions);
        assertRecovery(fixture);
    }

    private void assertFailed(Fixture fixture, TrackingInput input) throws Exception {
        assertEquals(Collections.singletonList(false), completions);
        assertTrue(input.closed);
        assertArrayEquals(wave(), Files.readAllBytes(fixture.source.toPath()));
        assertTrue(queued.isEmpty());
    }

    private static void assertRecovery(Fixture fixture) {
        try {
            assertArrayEquals(wave(), Files.readAllBytes(fixture.destination.toPath()));
            assertArrayEquals(METADATA, Files.readAllBytes(fixture.metadata.toPath()));
        } catch (IOException error) {
            throw new AssertionError(error);
        }
    }

    private Fixture fixture() throws Exception {
        File directory = temporary.newFolder();
        File source = new File(directory, "source.part");
        File destination = new File(directory,
                "zalo-call-1785150000000-incoming-deadbeef.processing.wav");
        Files.write(source.toPath(), wave());
        Files.write(new File(source + ".json").toPath(), METADATA);
        return new Fixture(source, destination, new File(destination + ".json"));
    }

    private static byte[] wave() {
        int dataLength = 96_000;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLength);
        buffer.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16);
        buffer.putShort((short) 1).putShort((short) 1).putInt(16_000).putInt(32_000);
        buffer.putShort((short) 2).putShort((short) 16);
        buffer.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLength);
        return buffer.array();
    }

    private record Fixture(File source, File destination, File metadata) {}

    private static class TrackingInput extends FilterInputStream {
        boolean closed;
        TrackingInput(File source) throws IOException { super(new FileInputStream(source)); }
        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
