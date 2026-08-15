package com.ez.zalopatch;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CallRecordingTranscoderTest extends InstrumentationTestCase {
    public void testSortableFilenamePreservesUnicodeNameAndOptionalPhone() {
        String withPhone = CallRecordingStore.buildDisplayName(
                1785150000000L, "Đặng / An", "+84 912 345 678");
        assertTrue(withPhone.matches(
                "\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2} - Đặng _ An - \\+84912345678\\.m4a"));

        String withoutPhone = CallRecordingStore.buildDisplayName(
                1785150000000L, "Nguyễn An", "");
        assertTrue(withoutPhone.endsWith(" - Nguyễn An.m4a"));
    }

    public void testPcmWaveConvertsToAacM4a() throws Exception {
        File directory = getInstrumentation().getTargetContext().getCacheDir();
        File wav = new File(directory, "call-recording-test.wav");
        File m4a = new File(directory, "call-recording-test.m4a");
        wav.delete();
        m4a.delete();
        try {
            writeSilenceWav(wav, 16_000, 1, 500);
            assertTrue(CallRecordingTranscoder.isPcmWave(wav));
            assertTrue(CallRecordingStore.isNativeImportReady(wav));

            CallRecordingTranscoder.wavToM4a(wav, m4a);

            assertTrue(m4a.isFile());
            assertTrue(m4a.length() > 0L);
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(m4a.getAbsolutePath());
                assertEquals(1, extractor.getTrackCount());
                MediaFormat format = extractor.getTrackFormat(0);
                assertEquals(MediaFormat.MIMETYPE_AUDIO_AAC,
                        format.getString(MediaFormat.KEY_MIME));
            } finally {
                extractor.release();
            }
        } finally {
            wav.delete();
            m4a.delete();
        }
    }

    public void testIncompleteNativeWaveIsNotReadyForImport() throws Exception {
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(),
                "call-recording-incomplete.part");
        file.delete();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        }
        try {
            assertFalse(CallRecordingStore.isNativeImportReady(file));
        } finally {
            file.delete();
        }
    }

    public void testTruncatedNativeWaveIsNotReadyForImport() throws Exception {
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(),
                "call-recording-truncated.part");
        file.delete();
        try {
            writeSilenceWav(file, 16_000, 1, 500);
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.setLength(output.length() - 100L);
            }
            assertFalse(CallRecordingStore.isNativeImportReady(file));
        } finally {
            file.delete();
        }
    }

    public void testIdentityAttestationCapturesSourceUid() throws Exception {
        assertEquals(Process.myUid(),
                CallRecordingImportProtocol.attest(CallRecordingImportProtocol.identity()));
    }

    public void testExplicitImportBroadcastResolvesFromZaloContext() throws Exception {
        Context moduleContext = getInstrumentation().getTargetContext();
        Context zaloContext = moduleContext.createPackageContext(
                "com.zing.zalo", Context.CONTEXT_IGNORE_SECURITY);
        File source = new File(moduleContext.getCacheDir(), "recording-provider-test.part");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        source.delete();
        try {
            writeSilenceWav(source, 16_000, 1, 100);
            IBinder sourceBinder = CallRecordingImportProtocol.source(source, value -> {
                accepted.set(value);
                completed.countDown();
            });
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(moduleContext.getPackageName(),
                    CallRecordingImportReceiver.class.getName()));
            Bundle extras = new Bundle();
            extras.putBinder(CallRecordingImportReceiver.EXTRA_SOURCE_BINDER, sourceBinder);
            extras.putString(CallRecordingImportReceiver.EXTRA_PENDING_NAME, "invalid");
            extras.putString(CallRecordingImportReceiver.EXTRA_DISPLAY_NAME, "Zalo contact");
            extras.putString(CallRecordingImportReceiver.EXTRA_PHONE_NUMBER, "");
            intent.putExtras(extras);
            zaloContext.sendBroadcast(intent);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertFalse(accepted.get());
        } finally {
            source.delete();
        }
    }

    private static void writeSilenceWav(
            File file, int sampleRate, int channels, int durationMs) throws IOException {
        int dataLength = sampleRate * channels * 2 * durationMs / 1000;
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt(output, 36 + dataLength);
            output.write(new byte[]{'W', 'A', 'V', 'E'});
            output.write(new byte[]{'f', 'm', 't', ' '});
            writeInt(output, 16);
            writeShort(output, 1);
            writeShort(output, channels);
            writeInt(output, sampleRate);
            writeInt(output, sampleRate * channels * 2);
            writeShort(output, channels * 2);
            writeShort(output, 16);
            output.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt(output, dataLength);
            output.write(new byte[dataLength]);
        }
    }

    private static void writeInt(FileOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(FileOutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }
}
