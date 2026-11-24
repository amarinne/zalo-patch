package com.ez.zalopatch;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CallRecordingStore {
    public static final Uri URI = Uri.parse("content://" + ConfigProvider.AUTHORITY
            + "/call_recordings");
    private static final String DIRECTORY = "call_recordings";
    private static final String CORRUPT_DIRECTORY = "corrupt";
    static final String REPORT_FINALIZATION = "report_call_recording_finalization";
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final String SHARED_DIRECTORY = "Recordings/Zalo Call Recordings";
    private static final Pattern PENDING_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part");
    private static final Pattern PROCESSING_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.processing\\.wav");
    private static final Pattern RAW_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})"
                    + "\\.processing\\.wav(?:\\.\\d+)?");
    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<String>());
    // Copying must not wait behind a long AAC conversion while a broadcast is pending.
    private static final CallRecordingTransfer TRANSFERS = new CallRecordingTransfer(
            Executors.newSingleThreadExecutor(), QUEUED, CallRecordingStore::syncDirectory);

    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final long startedAt;
        public final long size;
        public final long durationMs;
        public final boolean raw;
        final File rawFile;

        Entry(Uri uri, String name, long startedAt, long size, long durationMs,
                boolean raw, File rawFile) {
            this.uri = uri;
            this.name = name;
            this.startedAt = startedAt;
            this.size = size;
            this.durationMs = durationMs;
            this.raw = raw;
            this.rawFile = rawFile;
        }
    }

    private CallRecordingStore() {
    }

    public static String newPendingName(long startedAt, String direction) {
        String safeDirection = safeDirection(direction);
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format(Locale.US, "zalo-call-%013d-%s-%s.part",
                Math.max(0L, startedAt), safeDirection, nonce);
    }

    /** Returns descriptor ownership, not permission to delete the source. */
    static boolean enqueueImport(
            Context context,
            ParcelFileDescriptor source,
            String pendingName,
            String displayName,
            String phoneNumber,
            CallRecordingImportProtocol.Completion completion) throws Exception {
        Matcher matcher = PENDING_NAME.matcher(pendingName == null ? "" : pendingName);
        if (!matcher.matches()) {
            return false;
        }
        String base = pendingName.substring(0, pendingName.length() - ".part".length());
        File processing = new File(privateDirectory(context), base + ".processing.wav");
        Metadata metadata = new Metadata(parseLong(matcher.group(1)), matcher.group(2),
                safeDisplayName(displayName), safePhone(phoneNumber));
        byte[] recoveryMetadata = encodeMetadata(metadata);
        Context application = context.getApplicationContext();
        TRANSFERS.enqueue(new ParcelFileDescriptor.AutoCloseInputStream(source),
                processing, recoveryMetadata, CallRecordingTranscoder::isPcmWave, accepted -> {
                    // Register the wakeup before acknowledging the copy, so process death
                    // after acknowledgment cannot leave it without a scheduled conversion.
                    boolean scheduled = accepted && CallRecordingFinalizationJob.enqueue(application);
                    try {
                        completion.onComplete(accepted);
                    } finally {
                        if (!scheduled) {
                            reportFinalization(application, pendingName, 0L, accepted
                                    ? "Could not schedule conversion; private WAV preserved"
                                    : "Recording import incomplete; source or recovery WAV preserved");
                        }
                    }
                });
        return true;
    }

    public static void recover(Context context) {
        if (CallRecordingFinalizationJob.isScheduled(context)) return;
        for (File wav : recoveryFiles(context)) {
            try {
                if (eligible(context, wav, CallRecordingFinalization.read(wav))) {
                    CallRecordingFinalizationJob.enqueue(context);
                    return;
                }
            } catch (IOException failure) {
                logFinalization("recovery", failure.getClass().getSimpleName());
            }
        }
    }

    public static void recoverAsync(Context context) {
        Context application = context.getApplicationContext();
        new Thread(() -> recover(application), "call-recording-recovery").start();
    }

    public static List<Entry> list(Context context) {
        ArrayList<Entry> entries = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            String[] projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED
            };
            String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=? AND "
                    + MediaStore.Audio.Media.IS_PENDING + "=0";
            String[] args = new String[]{SHARED_DIRECTORY + "/"};
            try (Cursor cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
                if (cursor != null) {
                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                    int durationIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIndex);
                        entries.add(new Entry(Uri.withAppendedPath(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)),
                                cursor.getString(nameIndex), cursor.getLong(dateIndex) * 1000L,
                                cursor.getLong(sizeIndex), cursor.getLong(durationIndex), false, null));
                    }
                }
            }
        }
        addRawEntries(privateDirectory(context), entries);
        addRawEntries(new File(privateDirectory(context), CORRUPT_DIRECTORY), entries);
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.startedAt).reversed());
        return Collections.unmodifiableList(entries);
    }

    public static long totalSize(Context context) {
        long total = 0L;
        for (Entry entry : list(context)) {
            total += entry.size;
        }
        return total;
    }

    public static boolean delete(Context context, Entry entry) {
        if (entry.raw) {
            if (entry.rawFile == null) return false;
            String key = entry.rawFile.getAbsolutePath();
            if (!QUEUED.add(key)) {
                return false;
            }
            try (FileChannel channel = openForLock(entry.rawFile);
                 FileLock lock = channel.tryLock()) {
                if (lock == null || !entry.rawFile.delete()) return false;
                removeRecoveryFiles(entry.rawFile);
                return true;
            } catch (IOException | OverlappingFileLockException failure) {
                return false;
            } finally {
                QUEUED.remove(key);
            }
        }
        return context.getContentResolver().delete(entry.uri, null, null) > 0;
    }

    enum RetryResult { QUEUED, ALREADY_QUEUED, INVALID_AUDIO, UNAVAILABLE }

    static RetryResult retry(Context context, Entry entry) {
        if (entry == null || !entry.raw || entry.rawFile == null) {
            return RetryResult.UNAVAILABLE;
        }
        File wav = entry.rawFile;
        String key = wav.getAbsolutePath();
        if (!QUEUED.add(key)) return RetryResult.ALREADY_QUEUED;
        try (FileChannel channel = openForLock(wav); FileLock lock = channel.tryLock()) {
            if (lock == null) return RetryResult.ALREADY_QUEUED;
            if (!wav.isFile()) return RetryResult.UNAVAILABLE;
            if (!isNativeImportReady(wav) && !repairNativeImport(wav)) {
                updateSelfCheck(context, "failed", 0, "Raw WAV is not valid PCM audio");
                return RetryResult.INVALID_AUDIO;
            }
            CallRecordingFinalization.read(wav).resetForRetry(wav);
        } catch (OverlappingFileLockException busy) {
            return RetryResult.ALREADY_QUEUED;
        } catch (IOException | RuntimeException failure) {
            return RetryResult.UNAVAILABLE;
        } finally {
            QUEUED.remove(key);
        }
        return CallRecordingFinalizationJob.enqueue(context) ? RetryResult.QUEUED : RetryResult.UNAVAILABLE;
    }

    static boolean isPendingName(String name) {
        return name != null && PENDING_NAME.matcher(name).matches();
    }

    public static boolean isNativeImportReady(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.isPcmWave(file);
    }

    public static boolean repairNativeImport(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.repairHeader(file);
    }

    private static FileChannel openForLock(File wav) throws IOException {
        // Closing an encoder input descriptor can release process-owned locks on its inode.
        // Keep the lock on a separate file used only for this purpose.
        return FileChannel.open(lockFile(wav).toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
    }

    private static File lockFile(File wav) {
        return new File(wav.getPath() + ".lock");
    }

    private static List<File> recoveryFiles(Context context) {
        File directory = privateDirectory(context);
        ArrayList<File> files = new ArrayList<>();
        for (File folder : new File[]{directory, new File(directory, CORRUPT_DIRECTORY)}) {
            File[] raw = folder.listFiles((ignored, name) -> RAW_NAME.matcher(name).matches());
            if (raw != null) Collections.addAll(files, raw);
        }
        files.sort(CallRecordingFinalization.NEWEST_FIRST);
        return files;
    }

    private static boolean eligible(Context context, File wav, CallRecordingFinalization state) {
        return state.next() != CallRecordingFinalization.Next.DONE && (state.manual()
                || (wav.getParentFile().equals(privateDirectory(context))
                    && PROCESSING_NAME.matcher(wav.getName()).matches()));
    }

    static boolean finalizeNext(Context context, Runnable progress) {
        for (File wav : recoveryFiles(context)) {
            String key = wav.getAbsolutePath();
            if (!QUEUED.add(key)) continue;
            try (FileChannel channel = openForLock(wav); FileLock lock = channel.tryLock()) {
                if (lock != null && wav.isFile() && finalizePending(context, wav, progress)) {
                    return true;
                }
            } catch (NoSuchFileException | OverlappingFileLockException unavailable) {
                // Deleted, or held by another caller.
            } catch (IOException failure) {
                logFinalization("recovery", failure.getClass().getSimpleName());
            } finally {
                QUEUED.remove(key);
            }
        }
        return false;
    }

    private static boolean finalizePending(Context context, File wav, Runnable progress)
            throws IOException {
        CallRecordingFinalization state = CallRecordingFinalization.read(wav);
        if (!eligible(context, wav, state)) return false;
        CallRecordingFinalization.Next next = state.next();
        switch (next) {
            case CONVERT, VERIFY_PUBLICATION -> {
                state = state.start(wav);
                long size = finalizeNow(context, wav, state,
                        next == CallRecordingFinalization.Next.VERIFY_PUBLICATION, progress);
                if (size > 0L) {
                    CallRecordingFinalization.read(wav).complete(
                            wav, CallRecordingFinalization.Outcome.SAVED);
                }
            }
            case EXHAUSTED -> {
                state.complete(wav, CallRecordingFinalization.Outcome.FAILED);
                encodedFile(wav).delete();
            }
            case REPORT -> {
                // Keep the confirmed outcome across process death, including during reporting.
                // An unavailable result provider gets a separate, bounded retry budget.
                state = state.start(wav);
                boolean saved = state.outcome() == CallRecordingFinalization.Outcome.SAVED;
                sendFinalizationResult(context, wav, saved ? state.encodedSize() : 0L, saved ? ""
                        : "No confirmed shared M4A after conversion retries; private WAV preserved");
                if (saved) {
                    metadataFile(wav).delete();
                    if (wav.delete()) {
                        removeRecoveryFiles(wav);
                        return true;
                    }
                }
                state.finishReporting(wav);
            }
            case DONE -> throw new IllegalStateException("Finalization is already complete");
        }
        return true;
    }

    private static long finalizeNow(Context context, File wav, CallRecordingFinalization state,
            boolean verifyOnly, Runnable progress) {
        File encoded = encodedFile(wav);
        String stage = "publication recovery";
        try {
            Uri uri = state.mediaUri().isEmpty() ? null : Uri.parse(state.mediaUri());
            if (uri != null) {
                int pending = publicationState(context.getContentResolver(), uri);
                if (pending == 0) {
                    return verifiedSize(context.getContentResolver(), uri, state.encodedSize());
                }
                if (pending == -1) uri = null;
            }
            if (verifyOnly) return 0L;
            stage = "encoding";
            logFinalization(stage, "started");
            progress.run();
            encoded.delete();
            CallRecordingTranscoder.wavToM4a(wav, encoded, progress);
            stage = "publication";
            logFinalization(stage, "started");
            progress.run();
            Metadata metadata = metadataFor(wav);
            long size = publish(context, wav, state, uri, encoded, buildDisplayName(
                    metadata.startedAt, metadata.displayName, metadata.phoneNumber));
            logFinalization(stage, "completed");
            return size;
        } catch (Exception failure) {
            logFinalization(stage, failure.getClass().getSimpleName());
            return 0L;
        } finally {
            encoded.delete();
        }
    }

    private static File encodedFile(File wav) {
        return new File(wav.getParentFile(), wav.getName().replace(".processing.wav", ".m4a.tmp"));
    }

    private static void removeRecoveryFiles(File wav) {
        metadataFile(wav).delete();
        encodedFile(wav).delete();
        File state = CallRecordingFinalization.file(wav);
        state.delete();
        new File(state.getPath() + ".writing").delete();
        lockFile(wav).delete();
    }

    private static void sendFinalizationResult(Context context, File wav, long size, String error) {
        Matcher matcher = RAW_NAME.matcher(wav.getName());
        if (!matcher.matches()) return;
        String pendingName = "zalo-call-" + matcher.group(1) + "-" + matcher.group(2)
                + "-" + matcher.group(3) + ".part";
        Bundle result = new Bundle();
        result.putLong("size", size);
        result.putString("error", error);
        if (context.getContentResolver().call(
                ConfigProvider.URI, REPORT_FINALIZATION, pendingName, result) == null) {
            throw new IllegalStateException("Recording result was not accepted");
        }
    }

    static void reportFinalization(Context context, String pendingName, long size, String error) {
        if (!updateSelfCheck(context, size > 0L ? "active" : "failed", size, error)) {
            throw new IllegalStateException("Recording self-check could not be saved");
        }
        CallRecordingNotifier.finishFinalization(context, pendingName, size > 0L);
    }

    private static void logFinalization(String stage, String outcome) {
        if (HookConfig.isDebugEnabled()) {
            Log.i("ZaloPatch", "Call recording stage=" + stage + " result=" + outcome);
        }
    }

    private static void addRawEntries(File directory, List<Entry> entries) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            Matcher matcher = RAW_NAME.matcher(file.getName());
            if (!file.isFile() || !matcher.matches()) {
                continue;
            }
            Metadata metadata = metadataFor(file);
            long startedAt = metadata.startedAt > 0L
                    ? metadata.startedAt : parseLong(matcher.group(1));
            String name = buildDisplayName(startedAt,
                    metadata.displayName, metadata.phoneNumber)
                    .replace(".m4a", ".wav");
            entries.add(new Entry(Uri.fromFile(file), name, startedAt,
                    file.length(), 0L, true, file));
        }
    }

    private static Metadata metadataFor(File processing) {
        try {
            return readMetadata(metadataFile(processing));
        } catch (Exception ignored) {
            Matcher matcher = RAW_NAME.matcher(processing.getName());
            long startedAt = matcher.matches() ? parseLong(matcher.group(1)) : processing.lastModified();
            String direction = matcher.matches() ? matcher.group(2) : "unknown";
            return new Metadata(startedAt, direction, "Zalo contact", "");
        }
    }

    private static long publish(Context context, File wav, CallRecordingFinalization state,
            Uri uri, File source, String displayName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (uri == null) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Audio.Media.TITLE,
                    displayName.substring(0, displayName.length() - ".m4a".length()));
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED_DIRECTORY);
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Shared recording destination unavailable");
        }
        // Persist the row before writing. A restarted attempt can reopen the pending row
        // or recognize a row published just before the process died.
        long encodedSize = source.length();
        state.publishing(wav, uri.toString(), encodedSize);
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri, "wt")) {
                if (output == null) throw new IOException("Shared output unavailable");
                copy(input, output);
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Audio.Media.IS_PENDING, 0);
            if (resolver.update(uri, complete, null, null) != 1
                    || publicationState(resolver, uri) != 0) {
                throw new IOException("Shared recording was not published");
            }
            return verifiedSize(resolver, uri, encodedSize);
        } catch (Exception failure) {
            try {
                if (publicationState(resolver, uri) == 1) resolver.delete(uri, null, null);
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException(failure);
        }
    }

    // A missing row can be recreated. A failed query must not cause duplicate publication.
    private static int publicationState(ContentResolver resolver, Uri uri) throws IOException {
        try (Cursor cursor = resolver.query(uri, new String[]{MediaStore.Audio.Media.IS_PENDING},
                null, null, null)) {
            if (cursor == null) throw new IOException("Shared recording status unavailable");
            return cursor.moveToFirst() ? cursor.getInt(0) : -1;
        }
    }

    private static long verifiedSize(ContentResolver resolver, Uri uri, long expected)
            throws IOException {
        try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
            long size = file == null ? 0L : file.getStatSize();
            if (size <= 0L || size != expected) throw new IOException("Shared recording size differs");
            return size;
        }
    }

    static String buildDisplayName(long startedAt, String displayName, String phoneNumber) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US)
                .format(new Date(startedAt));
        StringBuilder name = new StringBuilder(timestamp).append(" - ")
                .append(sanitizeFilename(safeDisplayName(displayName)));
        String safePhone = safePhone(phoneNumber);
        if (!safePhone.isEmpty()) {
            name.append(" - ").append(safePhone);
        }
        return name.append(".m4a").toString();
    }

    private static String sanitizeFilename(String value) {
        String clean = value == null ? "" : value
                .replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", " ").trim();
        while (clean.endsWith(".")) {
            clean = clean.substring(0, clean.length() - 1).trim();
        }
        if (clean.isEmpty()) {
            clean = "Zalo contact";
        }
        return clean.length() > 80 ? clean.substring(0, 80).trim() : clean;
    }

    private static String safeDisplayName(String value) {
        return value == null || value.trim().isEmpty() ? "Zalo contact" : value.trim();
    }

    private static String safePhone(String value) {
        if (value == null) {
            return "";
        }
        boolean plus = value.trim().startsWith("+");
        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 8 || digits.length() > 15) {
            return "";
        }
        return plus ? "+" + digits : digits;
    }

    private static String safeDirection(String direction) {
        return "incoming".equals(direction) || "outgoing".equals(direction)
                ? direction : "unknown";
    }

    private static File privateDirectory(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        return directory;
    }

    private static File metadataFile(File processing) {
        return new File(processing.getParentFile(), processing.getName() + ".json");
    }

    private static void syncDirectory(File directory) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(directory.getAbsolutePath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(descriptor);
            } finally {
                Os.close(descriptor);
            }
        } catch (ErrnoException exception) {
            throw new IOException("Could not sync recording directory", exception);
        }
    }

    private static byte[] encodeMetadata(Metadata metadata) throws Exception {
        JSONObject json = new JSONObject();
        json.put("started_at", metadata.startedAt);
        json.put("direction", metadata.direction);
        json.put("display_name", metadata.displayName);
        json.put("phone_number", metadata.phoneNumber);
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("Recording metadata exceeds the recovery limit");
        }
        return bytes;
    }

    private static Metadata readMetadata(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = new byte[(int) Math.min(file.length(), MAX_METADATA_BYTES)];
            int count = input.read(bytes);
            if (count <= 0) {
                throw new IOException("Metadata is empty");
            }
            JSONObject json = new JSONObject(new String(bytes, 0, count, StandardCharsets.UTF_8));
            return new Metadata(json.optLong("started_at", 0L),
                    json.optString("direction", "unknown"),
                    safeDisplayName(json.optString("display_name", "")),
                    safePhone(json.optString("phone_number", "")));
        }
    }

    private static synchronized boolean updateSelfCheck(
            Context context, String status, long size, String error) {
        android.content.SharedPreferences preferences = TweakStore.preferences(context);
        String prefix = "selfcheck.calls.auto_record.storage.";
        int hits = preferences.getInt(prefix + "hit_count", 0);
        android.content.SharedPreferences.Editor editor = preferences.edit();
        editor.putString(prefix + "status", status);
        editor.putString(prefix + "target", "MediaStore shared M4A");
        editor.putString(prefix + "detail", size > 0L ? "format=m4a bytes=" + size : "");
        editor.putString(prefix + "error", error == null ? "" : error);
        editor.putInt(prefix + "install_count", 1);
        editor.putInt(prefix + "hit_count", "active".equals(status) ? hits + 1 : hits);
        editor.putLong(prefix + "updated_at", System.currentTimeMillis());
        return editor.commit();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class Metadata {
        final long startedAt;
        final String direction;
        final String displayName;
        final String phoneNumber;

        Metadata(long startedAt, String direction, String displayName, String phoneNumber) {
            this.startedAt = startedAt;
            this.direction = direction;
            this.displayName = displayName;
            this.phoneNumber = phoneNumber;
        }
    }
}
