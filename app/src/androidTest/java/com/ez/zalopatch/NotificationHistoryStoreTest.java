package com.ez.zalopatch;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

import junit.framework.TestCase;

public final class NotificationHistoryStoreTest extends TestCase {
    public void testRetentionTrimUsesNewestRows() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            NotificationHistoryStore.createSchema(db);
            insert(db, 100L, "old");
            insert(db, 300L, "newest");
            insert(db, 200L, "middle");

            NotificationHistoryStore.trim(db, 2);

            Cursor cursor = db.rawQuery(
                    "SELECT posted_at FROM zalo_notifications ORDER BY posted_at DESC, _id DESC", null);
            try {
                assertEquals(2, cursor.getCount());
                assertTrue(cursor.moveToFirst());
                assertEquals(300L, cursor.getLong(0));
                assertTrue(cursor.moveToNext());
                assertEquals(200L, cursor.getLong(0));
            } finally {
                cursor.close();
            }
        } finally {
            db.close();
        }
    }

    public void testHistoryExportKeepsPayloadAndPrivacyWarning() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            NotificationHistoryStore.createSchema(db);
            ContentValues values = new ContentValues();
            values.put("posted_at", 1234L);
            values.put("sbn_key", "key");
            String[] fields = {"channel_id", "category", "template", "title", "text",
                    "big_text", "lines", "ticker", "metadata"};
            for (String field : fields) {
                values.put(field, field + " \"quote\" \\ slash\nTiếng Việt 🔔\t\u0001");
            }
            values.put("promo", 1);
            values.put("cancelled", 0);
            assertTrue(db.insert("zalo_notifications", null, values) > 0L);

            JSONObject root = export(db, NotificationHistoryStore.RETENTION_UNLIMITED);
            JSONArray rows = root.getJSONArray("notifications");

            assertEquals(1, root.getInt("format_version"));
            assertEquals(5678L, root.getLong("exported_at"));
            assertTrue(root.getString("warning").contains("private"));
            assertEquals(1, rows.length());
            JSONObject row = rows.getJSONObject(0);
            assertEquals(1234L, row.getLong("posted_at"));
            assertEquals("key", row.getString("key"));
            for (String field : fields) {
                assertEquals(values.getAsString(field), row.getString(field));
            }
            assertTrue(row.getBoolean("promo"));
            assertFalse(row.getBoolean("cancelled"));
        }
    }

    public void testHistoryExportRespectsFiniteAndUnlimitedRetention() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            NotificationHistoryStore.createSchema(db);
            insert(db, 100L, "old");
            insert(db, 200L, "same-time-first");
            insert(db, 200L, "same-time-last");

            JSONArray limited = export(db, 2).getJSONArray("notifications");
            assertEquals(2, limited.length());
            assertEquals("same-time-last", limited.getJSONObject(0).getString("title"));
            assertEquals("same-time-first", limited.getJSONObject(1).getString("title"));
            assertEquals("", limited.getJSONObject(0).getString("text"));
            JSONArray unlimited = export(db, NotificationHistoryStore.RETENTION_UNLIMITED)
                    .getJSONArray("notifications");
            assertEquals(3, unlimited.length());
            assertEquals("old", unlimited.getJSONObject(2).getString("title"));
        }
    }

    public void testHistoryExportAllowsEmptyArchive() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            NotificationHistoryStore.createSchema(db);
            assertEquals(0, export(db, NotificationHistoryStore.RETENTION_UNLIMITED)
                    .getJSONArray("notifications").length());
        }
    }

    public void testHistoryExportPropagatesDatabaseFailure() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            // A missing table must fail instead of producing a successful empty archive.
            try {
                export(db, NotificationHistoryStore.RETENTION_UNLIMITED);
                fail("Expected database failure");
            } catch (SQLiteException expected) {
                // The document operation reports this failure to the user.
            }
        }
    }

    public void testHistoryExportPropagatesDestinationFailure() throws Exception {
        try (SQLiteDatabase db = SQLiteDatabase.create(null)) {
            NotificationHistoryStore.createSchema(db);
            insert(db, 100L, "synthetic notification");
            IOException failure = new IOException("Synthetic destination failure");
            Writer output = new Writer() {
                @Override
                public void write(char[] chars, int offset, int length) throws IOException {
                    throw failure;
                }

                @Override
                public void flush() { }

                @Override
                public void close() { }
            };
            try {
                NotificationHistoryStore.exportJson(db, 0, output, 5678L);
                fail("Expected destination failure");
            } catch (IOException expected) {
                assertSame(failure, expected);
            }
            assertEquals(1, export(db, 0).getJSONArray("notifications").length());
        }
    }

    private static JSONObject export(SQLiteDatabase db, int limit) throws Exception {
        StringWriter output = new StringWriter();
        NotificationHistoryStore.exportJson(db, limit, output, 5678L);
        return new JSONObject(output.toString());
    }

    public void testUnlimitedRetentionDoesNotTrimRows() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            NotificationHistoryStore.createSchema(db);
            insert(db, 100L, "old");
            insert(db, 200L, "new");

            NotificationHistoryStore.trim(db, NotificationHistoryStore.RETENTION_UNLIMITED);

            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM zalo_notifications", null);
            try {
                assertTrue(cursor.moveToFirst());
                assertEquals(2, cursor.getInt(0));
            } finally {
                cursor.close();
            }
        } finally {
            db.close();
        }
    }

    public void testMessagesBucketRequiresPayloadAndConversationShape() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            NotificationHistoryStore.createSchema(db);
            insert(db, 100L, "dm", "zalo_010_chat_channel_account", "msg",
                    "Notification$MessagingStyle", "hello", "");
            insert(db, 200L, "group", "zalo_020_chat_group_channel_group", "",
                    "", "group message", "");
            insert(db, 300L, "promo-message", "zalo_05_social_story_channel_story", "msg",
                    "BigTextStyle", "story text", "");
            insert(db, 400L, "call", "zalo_09_call_channel_account", "call",
                    "CallStyle", "Incoming voice call", "");
            insert(db, 500L, "metadata-only", "zalo_010_chat_channel_account", "msg",
                    "Notification$MessagingStyle", "", "channel=chat");
            insert(db, 600L, "system", "system_channel", "service",
                    "BigTextStyle", "Background service", "");
            insert(db, 700L, "suppressed-message", "zalo_010_chat_channel_account", "msg",
                    "Notification$MessagingStyle", "blocked message", "", 1);

            Cursor cursor = db.query(
                    "zalo_notifications",
                    new String[]{"title"},
                    NotificationHistoryStore.bucketSelection(
                            NotificationHistoryStore.Bucket.MESSAGES),
                    NotificationHistoryStore.bucketSelectionArgs(
                            NotificationHistoryStore.Bucket.MESSAGES),
                    null,
                    null,
                    "posted_at ASC");
            try {
                assertEquals(3, cursor.getCount());
                assertTrue(cursor.moveToFirst());
                assertEquals("dm", cursor.getString(0));
                assertTrue(cursor.moveToNext());
                assertEquals("group", cursor.getString(0));
                assertTrue(cursor.moveToNext());
                assertEquals("promo-message", cursor.getString(0));
            } finally {
                cursor.close();
            }
        } finally {
            db.close();
        }
    }

    private static void insert(SQLiteDatabase db, long postedAt, String title) {
        ContentValues values = new ContentValues();
        values.put("posted_at", postedAt);
        values.put("title", title);
        values.put("promo", 0);
        values.put("cancelled", 0);
        assertTrue(db.insert("zalo_notifications", null, values) > 0L);
    }

    private static void insert(
            SQLiteDatabase db,
            long postedAt,
            String title,
            String channel,
            String category,
            String template,
            String text,
            String metadata) {
        insert(db, postedAt, title, channel, category, template, text, metadata, 0);
    }

    private static void insert(
            SQLiteDatabase db,
            long postedAt,
            String title,
            String channel,
            String category,
            String template,
            String text,
            String metadata,
            int cancelled) {
        ContentValues values = new ContentValues();
        values.put("posted_at", postedAt);
        values.put("title", title);
        values.put("channel_id", channel);
        values.put("category", category);
        values.put("template", template);
        values.put("text", text);
        values.put("metadata", metadata);
        values.put("promo", 0);
        values.put("cancelled", cancelled);
        assertTrue(db.insert("zalo_notifications", null, values) > 0L);
    }
}
