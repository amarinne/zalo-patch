package com.ez.zalopatch;

import android.app.Notification;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class NotificationHistoryStore extends SQLiteOpenHelper {
    private static final Object RECORD_LOCK = new Object();
    public static final int RETENTION_UNLIMITED = 0;
    private static final String DATABASE_NAME = "notification_history.db";
    private static final int DATABASE_VERSION = 1;
    private static final long DEDUPE_WINDOW_MS = 60_000L;
    private static final String TABLE = "zalo_notifications";
    private final Context context;

    public enum Bucket {
        ALL,
        MESSAGES,
        SUPPRESSED,
        ALLOWED
    }

    public static final class Entry {
        public final long id;
        public final long postedAt;
        public final String key;
        public final String channelId;
        public final String category;
        public final String template;
        public final String title;
        public final String text;
        public final String bigText;
        public final String lines;
        public final String ticker;
        public final String metadata;
        public final boolean promo;
        public final boolean cancelled;

        Entry(
                long id,
                long postedAt,
                String key,
                String channelId,
                String category,
                String template,
                String title,
                String text,
                String bigText,
                String lines,
                String ticker,
                String metadata,
                boolean promo,
                boolean cancelled) {
            this.id = id;
            this.postedAt = postedAt;
            this.key = key;
            this.channelId = channelId;
            this.category = category;
            this.template = template;
            this.title = title;
            this.text = text;
            this.bigText = bigText;
            this.lines = lines;
            this.ticker = ticker;
            this.metadata = metadata;
            this.promo = promo;
            this.cancelled = cancelled;
        }

        public String summary() {
            String value = firstNonEmpty(text, bigText, lines, ticker, metadata);
            if (value.length() > 180) {
                return value.substring(0, 180);
            }
            return value;
        }
    }

    public NotificationHistoryStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        this.context = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createSchema(db);
    }

    static void createSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "posted_at INTEGER NOT NULL,"
                + "sbn_key TEXT,"
                + "channel_id TEXT,"
                + "category TEXT,"
                + "template TEXT,"
                + "title TEXT,"
                + "text TEXT,"
                + "big_text TEXT,"
                + "lines TEXT,"
                + "ticker TEXT,"
                + "metadata TEXT,"
                + "promo INTEGER NOT NULL DEFAULT 0,"
                + "cancelled INTEGER NOT NULL DEFAULT 0"
                + ")");
        db.execSQL("CREATE INDEX idx_zalo_notifications_posted_at ON " + TABLE + "(posted_at DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long record(String key, Notification notification, boolean promo, boolean cancelled) {
        if (notification == null) {
            return -1L;
        }
        return insertRecord(NotificationHistoryPayload.fromNotification(key, notification, promo, cancelled));
    }

    public long record(ContentValues source) {
        if (source == null) {
            return -1L;
        }
        ContentValues values = new ContentValues();
        values.put("posted_at", longValue(source, "posted_at", System.currentTimeMillis()));
        copyString(source, values, "sbn_key");
        copyString(source, values, "channel_id");
        copyString(source, values, "category");
        copyString(source, values, "template");
        copyString(source, values, "title");
        copyString(source, values, "text");
        copyString(source, values, "big_text");
        copyString(source, values, "lines");
        copyString(source, values, "ticker");
        copyString(source, values, "metadata");
        values.put("promo", intValue(source, "promo", 0));
        values.put("cancelled", intValue(source, "cancelled", 0));
        return insertRecord(values);
    }

    private long insertRecord(ContentValues values) {
        synchronized (RECORD_LOCK) {
            try {
                SQLiteDatabase db = getWritableDatabase();
                if (isRecentDuplicate(db, values)) {
                    return -2L;
                }
                long id = db.insert(TABLE, null, values);
                trim(db, retentionLimit());
                return id;
            } catch (SQLiteException ignored) {
                return -1L;
            }
        }
    }

    private boolean isRecentDuplicate(SQLiteDatabase db, ContentValues values) {
        long postedAt = longValue(values, "posted_at", System.currentTimeMillis());
        String[] columns = new String[]{
                "channel_id", "category", "template", "title", "text",
                "big_text", "lines", "promo", "cancelled"
        };
        StringBuilder selection = new StringBuilder("posted_at>=?");
        ArrayList<String> args = new ArrayList<>();
        args.add(String.valueOf(Math.max(0L, postedAt - DEDUPE_WINDOW_MS)));
        for (String column : columns) {
            selection.append(" AND ").append(column).append("=?");
            if ("promo".equals(column) || "cancelled".equals(column)) {
                args.add(String.valueOf(intValue(values, column, 0)));
            } else {
                args.add(safe(values.getAsString(column)));
            }
        }
        Cursor cursor = db.query(
                TABLE,
                new String[]{"_id"},
                selection.toString(),
                args.toArray(new String[0]),
                null,
                null,
                "_id DESC",
                "1");
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    public List<Entry> latest(int limit) {
        return latest(limit, Bucket.ALL);
    }

    public List<Entry> latest(int limit, Bucket bucket) {
        ArrayList<Entry> entries = new ArrayList<>();
        String selection = bucketSelection(bucket);
        String[] selectionArgs = bucketSelectionArgs(bucket);
        try {
            Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    null,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    "posted_at DESC, _id DESC",
                    limit > RETENTION_UNLIMITED ? String.valueOf(limit) : null);
            try {
                while (cursor.moveToNext()) {
                    entries.add(entry(cursor));
                }
            } finally {
                cursor.close();
            }
        } catch (SQLiteException ignored) {
            return new ArrayList<>();
        }
        return entries;
    }

    public Entry find(long id) {
        try {
            Cursor cursor = getReadableDatabase().query(
                    TABLE, null, "_id=?", new String[]{String.valueOf(id)},
                    null, null, null, "1");
            try {
                return cursor.moveToFirst() ? entry(cursor) : null;
            } finally {
                cursor.close();
            }
        } catch (SQLiteException ignored) {
            return null;
        }
    }

    public int count() {
        return count(Bucket.ALL);
    }

    public int count(Bucket bucket) {
        String selection = bucketSelection(bucket);
        String[] selectionArgs = bucketSelectionArgs(bucket);
        try {
            Cursor cursor = getReadableDatabase().query(
                    TABLE,
                    new String[]{"COUNT(*)"},
                    selection,
                    selectionArgs,
                    null,
                    null,
                    null);
            try {
                return cursor.moveToFirst() ? cursor.getInt(0) : 0;
            } finally {
                cursor.close();
            }
        } catch (SQLiteException ignored) {
            return 0;
        }
    }

    public long storageBytes() {
        File database = context.getDatabasePath(DATABASE_NAME);
        return length(database) + length(new File(database.getPath() + "-wal"))
                + length(new File(database.getPath() + "-shm"));
    }

    public String storageSummary() {
        return count() + " stored · " + formatBytes(storageBytes());
    }

    public int retentionLimit() {
        return SettingsStore.getInt(context, Tweaks.KEY_NOTIFICATION_HISTORY_RETENTION);
    }

    public void enforceRetention() {
        trim(getWritableDatabase(), retentionLimit());
    }

    public String exportJson() throws Exception {
        return encodeEntries(latest(retentionLimit()), System.currentTimeMillis());
    }

    static String encodeEntries(List<Entry> entries, long exportedAt) throws Exception {
        JSONArray rows = new JSONArray();
        for (Entry entry : entries) {
            JSONObject row = new JSONObject();
            row.put("posted_at", entry.postedAt);
            row.put("key", entry.key);
            row.put("channel_id", entry.channelId);
            row.put("category", entry.category);
            row.put("template", entry.template);
            row.put("title", entry.title);
            row.put("text", entry.text);
            row.put("big_text", entry.bigText);
            row.put("lines", entry.lines);
            row.put("ticker", entry.ticker);
            row.put("metadata", entry.metadata);
            row.put("promo", entry.promo);
            row.put("cancelled", entry.cancelled);
            rows.put(row);
        }
        JSONObject root = new JSONObject();
        root.put("format_version", 1);
        root.put("exported_at", exportedAt);
        root.put("warning", "Contains private Zalo notification content");
        root.put("notifications", rows);
        return root.toString(2);
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    static void trim(SQLiteDatabase db, int limit) {
        if (limit <= RETENTION_UNLIMITED) {
            return;
        }
        db.execSQL("DELETE FROM " + TABLE + " WHERE _id NOT IN ("
                + "SELECT _id FROM " + TABLE + " ORDER BY posted_at DESC, _id DESC LIMIT " + limit
                + ")");
    }

    private static long length(File file) {
        return file.exists() ? file.length() : 0L;
    }

    static String bucketSelection(Bucket bucket) {
        if (bucket == Bucket.MESSAGES) {
            return "((TRIM(COALESCE(text, ''))<>'' OR TRIM(COALESCE(big_text, ''))<>''"
                    + " OR TRIM(COALESCE(lines, ''))<>'' OR TRIM(COALESCE(ticker, ''))<>'')"
                    + " AND (COALESCE(category, '')=? OR COALESCE(channel_id, '') LIKE ?"
                    + " OR COALESCE(channel_id, '') LIKE ? OR COALESCE(template, '') LIKE ?)"
                    + " AND cancelled=0"
                    + " AND COALESCE(category, '')<>?"
                    + " AND COALESCE(channel_id, '') NOT LIKE ?"
                    + " AND COALESCE(channel_id, '') NOT LIKE ?"
                    + " AND COALESCE(channel_id, '') NOT LIKE ?)";
        }
        if (bucket == Bucket.SUPPRESSED || bucket == Bucket.ALLOWED) {
            return "cancelled=?";
        }
        return null;
    }

    static String[] bucketSelectionArgs(Bucket bucket) {
        if (bucket == Bucket.MESSAGES) {
            return new String[]{
                    Notification.CATEGORY_MESSAGE,
                    "zalo_010_chat_channel_%",
                    "zalo_020_chat_group_channel_%",
                    "%MessagingStyle",
                    Notification.CATEGORY_CALL,
                    "zalo_03_call_channel_%",
                    "zalo_09_call_channel_%",
                    "zalo_10_db_action_channel_%"
            };
        }
        if (bucket == Bucket.SUPPRESSED || bucket == Bucket.ALLOWED) {
            return new String[]{bucket == Bucket.SUPPRESSED ? "1" : "0"};
        }
        return null;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KiB", kib);
        }
        return String.format(java.util.Locale.US, "%.1f MiB", kib / 1024.0);
    }

    private Entry entry(Cursor cursor) {
        return new Entry(
                getLong(cursor, "_id"),
                getLong(cursor, "posted_at"),
                getString(cursor, "sbn_key"),
                getString(cursor, "channel_id"),
                getString(cursor, "category"),
                getString(cursor, "template"),
                getString(cursor, "title"),
                getString(cursor, "text"),
                getString(cursor, "big_text"),
                getString(cursor, "lines"),
                getString(cursor, "ticker"),
                getString(cursor, "metadata"),
                getInt(cursor, "promo") == 1,
                getInt(cursor, "cancelled") == 1);
    }

    private static int getInt(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long getLong(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static String getString(Cursor cursor, String column) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(column));
        return value == null ? "" : value;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static void copyString(ContentValues source, ContentValues target, String key) {
        String value = source.getAsString(key);
        target.put(key, safe(value));
    }

    private static int intValue(ContentValues values, String key, int fallback) {
        Integer value = values.getAsInteger(key);
        return value == null ? fallback : value;
    }

    private static long longValue(ContentValues values, String key, long fallback) {
        Long value = values.getAsLong(key);
        return value == null ? fallback : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
