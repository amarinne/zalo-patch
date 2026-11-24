package com.ez.zalopatch;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallRecordingFinalizationTest {
    @Rule public TemporaryFolder recordings = new TemporaryFolder();

    @Test
    public void newlyAcceptedWaveGoesAheadOfRetainedWaves() throws IOException {
        File older = wave("older", 1000L);
        File newer = wave("newer", 2000L);
        File fresh = wave("fresh", 3000L);
        List<File> queue = new ArrayList<>(List.of(older, newer));
        queue.add(fresh);
        queue.sort(CallRecordingFinalization.NEWEST_FIRST);
        assertEquals(List.of(fresh, newer, older), queue);
    }

    @Test
    public void interruptedAttemptsConsumeBudgetBeforeAnyResultAndPreserveWave() throws IOException {
        File wav = wave("interrupted", 1000L);
        for (int started = 0; started < CallRecordingFinalization.MAX_ATTEMPTS; started++) {
            CallRecordingFinalization recovered = CallRecordingFinalization.read(wav);
            assertEquals(started, recovered.attempts());
            assertEquals(CallRecordingFinalization.Next.CONVERT, recovered.next());
            recovered.start(wav);
            // A new process can read the charge even if no conversion callback ran.
        }
        CallRecordingFinalization exhausted = CallRecordingFinalization.read(wav);
        assertEquals(CallRecordingFinalization.Next.EXHAUSTED, exhausted.next());
        exhausted.complete(wav, CallRecordingFinalization.Outcome.FAILED);
        CallRecordingFinalization report = CallRecordingFinalization.read(wav);
        assertEquals(CallRecordingFinalization.Outcome.FAILED, report.outcome());
        assertEquals(CallRecordingFinalization.Next.REPORT, report.next());
        report.finishReporting(wav);
        assertEquals(CallRecordingFinalization.Next.DONE, CallRecordingFinalization.read(wav).next());
        assertTrue(wav.isFile());
    }

    @Test
    public void exhaustedPublishedRowGetsOneVerificationWithoutAnotherConversion() throws IOException {
        File wav = wave("publication", 1000L);
        String uri = "content://media/external/audio/media/7";
        new CallRecordingFinalization(CallRecordingFinalization.MAX_ATTEMPTS - 1,
                false, "", 0L, CallRecordingFinalization.Outcome.PENDING)
                .start(wav).publishing(wav, uri, 4321L);
        CallRecordingFinalization recovered = CallRecordingFinalization.read(wav);
        assertEquals(CallRecordingFinalization.Next.VERIFY_PUBLICATION, recovered.next());
        assertEquals(4321L, recovered.encodedSize());
        recovered.start(wav);
        assertEquals(CallRecordingFinalization.Next.EXHAUSTED,
                CallRecordingFinalization.read(wav).next());

        recovered.resetForRetry(wav);
        CallRecordingFinalization retry = CallRecordingFinalization.read(wav);
        assertTrue(retry.manual());
        assertEquals(CallRecordingFinalization.Next.CONVERT, retry.next());
        assertEquals(uri, retry.mediaUri());
        assertEquals(4321L, retry.encodedSize());
    }

    @Test
    public void interruptedResultReportingRetainsTheConfirmedOutcomeWithABoundedRetry()
            throws IOException {
        File wav = wave("saved", 1000L);
        CallRecordingFinalization.read(wav).start(wav)
                .publishing(wav, "content://media/external/audio/media/8", 900L);
        CallRecordingFinalization.read(wav).complete(wav, CallRecordingFinalization.Outcome.SAVED);
        CallRecordingFinalization.read(wav).start(wav);

        CallRecordingFinalization retry = CallRecordingFinalization.read(wav);
        assertEquals(CallRecordingFinalization.Next.REPORT, retry.next());
        assertEquals(CallRecordingFinalization.Outcome.SAVED, retry.outcome());
        assertEquals(900L, retry.encodedSize());
        retry.start(wav);
        assertEquals(CallRecordingFinalization.Next.DONE, CallRecordingFinalization.read(wav).next());
    }

    @Test
    public void watchdogBoundsCodecSetupTeardownAndPublicationWithoutHeartbeats() {
        CallRecordingFinalization.Watchdog watchdog = new CallRecordingFinalization.Watchdog(100L);
        assertFalse(watchdog.expired(60_099L));
        assertTrue(watchdog.expired(60_100L));

        watchdog.progress(50_000L);
        assertFalse(watchdog.expired(109_999L));
        assertTrue(watchdog.expired(110_000L));
    }

    private File wave(String name, long modified) throws IOException {
        File wav = recordings.newFile(name + ".processing.wav");
        Files.setLastModifiedTime(wav.toPath(), FileTime.fromMillis(modified));
        return wav;
    }
}
