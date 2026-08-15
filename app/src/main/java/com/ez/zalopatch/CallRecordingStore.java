package com.ez.zalopatch;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CallRecordingStore {
    public static final Uri URI = Uri.parse("content://" + ConfigProvider.AUTHORITY
            + "/call_recordings");
    private static final String DIRECTORY = "call_recordings";
    private static final String CORRUPT_DIRECTORY = "corrupt";
    private static final String RECOVERY_ATTEMPTS_PREFIX = "call_recording_recovery_attempts.";
    private static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final String SHARED_DIRECTORY = "Recordings/Zalo Call Recordings";
    private static final Pattern PENDING_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.part");
    private static final Pattern PROCESSING_NAME = Pattern.compile(
            "zalo-call-(\\d{13})-(incoming|outgoing|unknown)-([0-9a-f]{8})\\.processing\\.wav");
    private static final ExecutorService FINALIZER = Executors.newSingleThreadExecutor();
    private static final Set<String> QUEUED = Collections.synchronizedSet(new HashSet<String>());

    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final long startedAt;
        public final long size;
        public final long durationMs;

        Entry(Uri uri, String name, long startedAt, long size, long durationMs) {
            this.uri = uri;
            this.name = name;
            this.startedAt = startedAt;
            this.size = size;
            this.durationMs = durationMs;
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

    static boolean enqueueImport(
            Context context,
            ParcelFileDescriptor source,
            String pendingName,
            String displayName,
            String phoneNumber) {
        Matcher matcher = PENDING_NAME.matcher(pendingName == null ? "" : pendingName);
        if (!matcher.matches()) {
            return false;
        }
        String base = pendingName.substring(0, pendingName.length() - ".part".length());
        File processing = new File(privateDirectory(context), base + ".processing.wav");
        Metadata metadata = new Metadata(parseLong(matcher.group(1)), matcher.group(2),
                safeDisplayName(displayName), safePhone(phoneNumber));
        queueImport(context.getApplicationContext(), source, processing, metadata);
        return true;
    }

    public static void recover(Context context) {
        File[] files = privateDirectory(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!PROCESSING_NAME.matcher(file.getName()).matches()) {
                continue;
            }
            try {
                queue(context.getApplicationContext(), file, readMetadata(metadataFile(file)), true);
            } catch (Exception exception) {
                recordRecoveryFailure(context, file);
                updateSelfCheck(context, "failed", 0, "Could not recover pending conversion");
            }
        }
    }

    public static void recoverAsync(Context context) {
        Context applicationContext = context.getApplicationContext();
        FINALIZER.execute(() -> recover(applicationContext));
    }

    public static List<Entry> list(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Collections.emptyList();
        }
        ArrayList<Entry> entries = new ArrayList<>();
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED
        };
        String selection = MediaStore.Audio.Media.RELATIVE_PATH + "=?";
        String[] args = new String[]{SHARED_DIRECTORY + "/"};
        try (Cursor cursor = context.getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor == null) {
                return Collections.emptyList();
            }
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
                        cursor.getLong(sizeIndex), cursor.getLong(durationIndex)));
            }
        }
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
        return context.getContentResolver().delete(entry.uri, null, null) > 0;
    }

    static boolean isPendingName(String name) {
        return name != null && PENDING_NAME.matcher(name).matches();
    }

    public static boolean isNativeImportReady(File file) {
        return file != null && file.isFile() && CallRecordingTranscoder.isPcmWave(file);
    }

    private static void queue(
            Context context, File processing, Metadata metadata, boolean recovery) {
        String key = processing.getAbsolutePath();
        if (!QUEUED.add(key)) {
            return;
        }
        FINALIZER.execute(() -> {
            try {
                if (finalizeNow(context, processing, metadata)) {
                    clearRecoveryFailures(context, processing);
                } else if (recovery) {
                    recordRecoveryFailure(context, processing);
                }
            } finally {
                QUEUED.remove(key);
            }
        });
    }

    private static void queueImport(
            Context context,
            ParcelFileDescriptor source,
            File processing,
            Metadata metadata) {
        String key = processing.getAbsolutePath();
        if (!QUEUED.add(key)) {
            closeQuietly(source);
            return;
        }
        FINALIZER.execute(() -> {
            try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(source);
                 OutputStream output = new FileOutputStream(processing)) {
                copy(input, output);
            } catch (Throwable throwable) {
                processing.delete();
                updateSelfCheck(context, "failed", 0,
                        "Recording import failed: " + throwable.getClass().getSimpleName());
                CallRecordingNotifier.finishFailed(context);
                QUEUED.remove(key);
                return;
            }
            try {
                if (!CallRecordingTranscoder.isPcmWave(processing)) {
                    processing.delete();
                    updateSelfCheck(context, "failed", 0,
                            "Native WAV invalid after transfer");
                    CallRecordingNotifier.finishFailed(context);
                    return;
                }
                writeMetadata(metadataFile(processing), metadata);
                finalizeNow(context, processing, metadata);
            } catch (Throwable throwable) {
                processing.delete();
                metadataFile(processing).delete();
                updateSelfCheck(context, "failed", 0,
                        "Recording metadata failed: " + throwable.getClass().getSimpleName());
                CallRecordingNotifier.finishFailed(context);
            } finally {
                QUEUED.remove(key);
            }
        });
    }

    private static boolean finalizeNow(Context context, File wav, Metadata metadata) {
        File encoded = new File(privateDirectory(context),
                wav.getName().replace(".processing.wav", ".m4a.tmp"));
        try {
            CallRecordingTranscoder.wavToM4a(wav, encoded);
            Uri saved = publish(context, encoded, buildDisplayName(
                    metadata.startedAt, metadata.displayName, metadata.phoneNumber));
            if (saved == null) {
                throw new IOException("Shared recording destination unavailable");
            }
            long size = encoded.length();
            wav.delete();
            encoded.delete();
            metadataFile(wav).delete();
            updateSelfCheck(context, "active", size, "");
            CallRecordingNotifier.finishSaved(context);
            return true;
        } catch (Throwable throwable) {
            encoded.delete();
            updateSelfCheck(context, "failed", 0,
                    "M4A conversion failed: " + throwable.getClass().getSimpleName());
            CallRecordingNotifier.finishFailed(context);
            return false;
        }
    }

    private static void recordRecoveryFailure(Context context, File processing) {
        android.content.SharedPreferences preferences = TweakStore.preferences(context);
        String key = RECOVERY_ATTEMPTS_PREFIX + processing.getName();
        int attempts = preferences.getInt(key, 0) + 1;
        if (attempts < MAX_RECOVERY_ATTEMPTS) {
            preferences.edit().putInt(key, attempts).apply();
            return;
        }
        if (quarantine(context, processing)) {
            preferences.edit().remove(key).apply();
        } else {
            preferences.edit().putInt(key, attempts).apply();
        }
    }

    private static void clearRecoveryFailures(Context context, File processing) {
        TweakStore.preferences(context).edit()
                .remove(RECOVERY_ATTEMPTS_PREFIX + processing.getName()).apply();
    }

    private static boolean quarantine(Context context, File processing) {
        File directory = new File(privateDirectory(context), CORRUPT_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            return false;
        }
        File target = uniqueQuarantineFile(directory, processing.getName());
        File metadata = metadataFile(processing);
        File metadataTarget = metadataFile(target);
        if (!processing.renameTo(target)) {
            return false;
        }
        if (metadata.isFile() && !metadata.renameTo(metadataTarget)) {
            target.renameTo(processing);
            return false;
        }
        return true;
    }

    private static File uniqueQuarantineFile(File directory, String name) {
        File candidate = new File(directory, name);
        for (int suffix = 1; candidate.exists() || metadataFile(candidate).exists(); suffix++) {
            candidate = new File(directory, name + "." + suffix);
        }
        return candidate;
    }

    private static Uri publish(Context context, File source, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("Shared call recordings require Android 10 or newer");
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.TITLE,
                displayName.substring(0, displayName.length() - ".m4a".length()));
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, SHARED_DIRECTORY);
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return null;
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = resolver.openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IOException("Shared output unavailable");
            }
            copy(input, output);
        } catch (Throwable throwable) {
            resolver.delete(uri, null, null);
            if (throwable instanceof IOException) {
                throw (IOException) throwable;
            }
            throw new IOException(throwable);
        }
        ContentValues complete = new ContentValues();
        complete.put(MediaStore.Audio.Media.IS_PENDING, 0);
        resolver.update(uri, complete, null, null);
        return uri;
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

    private static void writeMetadata(File file, Metadata metadata) throws Exception {
        JSONObject json = new JSONObject();
        json.put("started_at", metadata.startedAt);
        json.put("direction", metadata.direction);
        json.put("display_name", metadata.displayName);
        json.put("phone_number", metadata.phoneNumber);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Metadata readMetadata(File file) throws Exception {
        byte[] bytes;
        try (FileInputStream input = new FileInputStream(file)) {
            bytes = new byte[(int) Math.min(file.length(), 16 * 1024L)];
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

    private static void updateSelfCheck(Context context, String status, long size, String error) {
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
        editor.apply();
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) {
            return;
        }
        try {
            descriptor.close();
        } catch (IOException ignored) {
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
