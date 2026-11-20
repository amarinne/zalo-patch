package com.ez.zalopatch;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class CallRecordingEncodingDeadlineTest {
    @Test
    public void codecWithoutAudioTimesOutBeforeTheWholeRecordingDeadline() throws IOException {
        CallRecordingTranscoder.EncodingDeadline deadline =
                new CallRecordingTranscoder.EncodingDeadline(100L, 5_000L);
        deadline.check(30_099L);
        assertThrows(CallRecordingTranscoder.NoCodecProgressException.class,
                () -> deadline.check(30_100L));
    }

    @Test
    public void samplesKeepEncodingAliveButDoNotExtendItsTotalBudget() throws IOException {
        CallRecordingTranscoder.EncodingDeadline deadline =
                new CallRecordingTranscoder.EncodingDeadline(0L, 60_000L);
        for (long now = 20_000L; now < 720_000L; now += 20_000L) {
            deadline.check(now);
            deadline.sampleWritten(now);
        }
        assertEquals("AAC transcode deadline exceeded",
                assertThrows(IOException.class, () -> deadline.check(720_000L)).getMessage());
    }
}
