package com.ez.zalopatch;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.content.Intent;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.os.IBinder;
import android.os.Process;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
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

    public void testNativeWaveHeaderRepairRestoresImportReadiness() throws Exception {
        File file = new File(getInstrumentation().getTargetContext().getCacheDir(),
                "call-recording-unfinalized.part");
        file.delete();
        try {
            writeSilenceWav(file, 16_000, 1, 500);
            try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                output.seek(4L);
                writeLittleEndianInt(output, 0);
                output.seek(40L);
                writeLittleEndianInt(output, 0);
            }
            assertFalse(CallRecordingStore.isNativeImportReady(file));
            assertTrue(CallRecordingStore.repairNativeImport(file));
            assertTrue(CallRecordingStore.isNativeImportReady(file));
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                assertEquals(file.length() - 8L, readLittleEndianInt(input, 4L));
                assertEquals(file.length() - 44L, readLittleEndianInt(input, 40L));
            }
        } finally {
            file.delete();
        }
    }

    public void testFailedRawWaveRemainsInCatalogAndCanBeDeleted() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "call_recordings");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File raw = new File(directory,
                "zalo-call-1785150000000-incoming-deadbeef.processing.wav");
        raw.delete();
        try (FileOutputStream output = new FileOutputStream(raw)) {
            output.write(new byte[]{'R', 'I', 'F', 'F'});
        }
        try {
            CallRecordingStore.Entry found = null;
            for (CallRecordingStore.Entry entry : CallRecordingStore.list(context)) {
                if (entry.raw && entry.uri.equals(android.net.Uri.fromFile(raw))) {
                    found = entry;
                    break;
                }
            }
            assertNotNull("Retained raw WAV missing from recording catalog", found);
            assertFalse(CallRecordingStore.retry(context, found));
            assertTrue("Failed retry deleted retained raw WAV", raw.isFile());
            assertTrue(CallRecordingStore.delete(context, found));
            assertFalse(raw.exists());
        } finally {
            raw.delete();
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

    public void testOversizedRecoveryMetadataDoesNotAcknowledgeOrConsumeSource() throws Exception {
        Context base = getInstrumentation().getTargetContext();
        File directory = Files.createTempDirectory(base.getCacheDir().toPath(), "recording-metadata-").toFile();
        File source = new File(directory, "source.part");
        Context context = new ContextWrapper(base) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
        };
        AtomicBoolean completed = new AtomicBoolean();
        try {
            writeSilenceWav(source, 16_000, 1, 100);
            char[] name = new char[16 * 1024];
            Arrays.fill(name, 'x');
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                    source, ParcelFileDescriptor.MODE_READ_ONLY)) {
                try {
                    CallRecordingStore.enqueueImport(context, descriptor,
                            "zalo-call-1785150000000-incoming-deadbeef.part", new String(name), "",
                            accepted -> completed.set(true));
                    fail("Metadata unreadable by recovery was accepted");
                } catch (IOException expected) {
                    assertTrue(descriptor.getFileDescriptor().valid());
                    assertTrue(CallRecordingStore.isNativeImportReady(source));
                    assertFalse(completed.get());
                }
            }
        } finally {
            source.delete();
            new File(directory, "call_recordings").delete();
            directory.delete();
        }
    }

    public void testZeroRowPublicationKeepsRecoveryAudioAndDoesNotReportSaved() throws Exception {
        assertPublicationOutcome(0, false, false);
    }

    public void testThrownPublicationKeepsRecoveryAudioAndDeletesOnlyOwnedRow() throws Exception {
        assertPublicationOutcome(0, true, false);
    }

    public void testPublicationCleanupFailureStillKeepsRecoveryAudio() throws Exception {
        assertPublicationOutcome(0, false, true);
    }

    public void testConfirmedPublicationRemovesRecoveryAudioAndReportsSaved() throws Exception {
        assertPublicationOutcome(1, false, false);
    }

    private void assertPublicationOutcome(int updated, boolean throwUpdate, boolean throwDelete)
            throws Exception {
        Context base = getInstrumentation().getTargetContext();
        String preferencesName = "recording-publication-fixture-" + UUID.randomUUID();
        File directory = Files.createTempDirectory(base.getCacheDir().toPath(),
                "recording-publication-").toFile();
        File recordings = new File(directory, "call_recordings");
        assertTrue(recordings.mkdir());
        File raw = new File(recordings,
                "zalo-call-1785150000000-incoming-deadbeef.processing.wav");
        File metadata = new File(raw + ".json");
        File published = new File(directory, "published.m4a");
        PublicationFixture provider = new PublicationFixture(published, updated, throwUpdate, throwDelete);
        SharedPreferences preferences = base.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        // Keep UI localization and self-check state inside this fixture's preferences.
        preferences.edit().putString(UiSettings.KEY_LANGUAGE, UiSettings.LANGUAGE_ENGLISH).commit();
        Context context = new ContextWrapper(base) {
            @Override public File getFilesDir() { return directory; }
            @Override public Context getApplicationContext() { return this; }
            @Override public ContentResolver getContentResolver() { return ContentResolver.wrap(provider); }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return preferences;
            }
            @Override public Context createConfigurationContext(Configuration configuration) { return this; }
            @Override public Object getSystemService(String name) {
                return NOTIFICATION_SERVICE.equals(name) ? null : super.getSystemService(name);
            }
        };
        ProviderInfo info = new ProviderInfo();
        info.authority = "media";
        provider.attachInfo(context, info);
        try {
            writeSilenceWav(raw, 16_000, 1, 500);
            byte[] audioBefore = Files.readAllBytes(raw.toPath());
            byte[] metadataBefore = ("{\"started_at\":1785150000000,\"direction\":\"incoming\","
                    + "\"display_name\":\"Fixture\",\"phone_number\":\"\"}")
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(metadata.toPath(), metadataBefore);
            Method readMetadata = CallRecordingStore.class.getDeclaredMethod("metadataFor", File.class);
            readMetadata.setAccessible(true);
            Object parsed = readMetadata.invoke(null, raw);
            Method finalizeNow = CallRecordingStore.class.getDeclaredMethod(
                    "finalizeNow", Context.class, File.class, parsed.getClass());
            finalizeNow.setAccessible(true);
            boolean saved = (Boolean) finalizeNow.invoke(null, context, raw, parsed);
            assertEquals(1, provider.updates);
            assertTrue("Fixture must reach publication with encoded audio", provider.bytesAtUpdate > 0);
            boolean expectedSaved = updated == 1 && !throwUpdate;
            assertEquals(expectedSaved, saved);
            String prefix = "selfcheck.calls.auto_record.storage.";
            assertEquals(expectedSaved ? "active" : "failed", preferences.getString(prefix + "status", ""));
            assertEquals(expectedSaved ? 1 : 0, preferences.getInt(prefix + "hit_count", 0));
            assertEquals(expectedSaved ? 0 : 1, provider.deletes);
            if (expectedSaved) {
                assertFalse(raw.exists());
                assertFalse(metadata.exists());
                assertTrue(published.length() > 0);
            } else {
                assertTrue(Arrays.equals(audioBefore, Files.readAllBytes(raw.toPath())));
                assertTrue(Arrays.equals(metadataBefore, Files.readAllBytes(metadata.toPath())));
                assertEquals(throwDelete, published.exists());
            }
        } finally {
            for (File file : recordings.listFiles()) file.delete();
            recordings.delete();
            published.delete();
            directory.delete();
            base.deleteSharedPreferences(preferencesName);
        }
    }

    /** An in-process provider. No operation reaches the device's MediaStore. */
    private static final class PublicationFixture extends ContentProvider {
        private final Uri owned = Uri.parse("content://media/external/audio/media/424242");
        private final File output;
        private final int updated;
        private final boolean throwUpdate;
        private final boolean throwDelete;
        int updates;
        int deletes;
        long bytesAtUpdate;

        PublicationFixture(File output, int updated, boolean throwUpdate, boolean throwDelete) {
            this.output = output;
            this.updated = updated;
            this.throwUpdate = throwUpdate;
            this.throwDelete = throwDelete;
        }

        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "audio/mp4"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String sortOrder) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri uri, ContentValues values) {
            assertEquals(Integer.valueOf(1), values.getAsInteger(MediaStore.Audio.Media.IS_PENDING));
            return owned;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            assertEquals(owned, uri);
            return ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            assertEquals(owned, uri);
            assertEquals(Integer.valueOf(0), values.getAsInteger(MediaStore.Audio.Media.IS_PENDING));
            updates++;
            bytesAtUpdate = output.length();
            if (throwUpdate) throw new IllegalStateException("Injected publication failure");
            return updated;
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            assertEquals(owned, uri);
            deletes++;
            if (throwDelete) throw new IllegalStateException("Injected cleanup failure");
            return output.delete() ? 1 : 0;
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

    private static void writeLittleEndianInt(RandomAccessFile output, int value)
            throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static long readLittleEndianInt(RandomAccessFile input, long offset)
            throws IOException {
        input.seek(offset);
        return (input.readUnsignedByte())
                | ((long) input.readUnsignedByte() << 8)
                | ((long) input.readUnsignedByte() << 16)
                | ((long) input.readUnsignedByte() << 24);
    }
}
