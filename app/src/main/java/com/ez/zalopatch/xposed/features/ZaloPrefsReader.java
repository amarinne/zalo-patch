package com.ez.zalopatch.xposed.features;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.LinkedHashMap;
import java.util.Map;

/** Narrow, ambiguity-safe reads from Zalo's own preference provider. */
final class ZaloPrefsReader {
    private static final Uri ROOT =
            Uri.parse("content://com.zing.zalo.db.preferencesprovider");
    private static final String AUTO_BACKUP_PREFIX = "config_auto_backup_v3_";

    enum AutoBackup {
        ENABLED,
        DISABLED,
        UNKNOWN,
        AMBIGUOUS
    }

    private ZaloPrefsReader() {
    }

    static AutoBackup autoBackup(Context context) {
        Map<String, String> values = new LinkedHashMap<>();
        try (Cursor cursor = context.getContentResolver().query(ROOT,
                new String[]{"key", "value"}, "key LIKE ?",
                new String[]{AUTO_BACKUP_PREFIX + "%"}, null)) {
            if (cursor == null) {
                return AutoBackup.UNKNOWN;
            }
            int keyIndex = cursor.getColumnIndex("key");
            int valueIndex = cursor.getColumnIndex("value");
            while (keyIndex >= 0 && valueIndex >= 0 && cursor.moveToNext()) {
                String key = cursor.getString(keyIndex);
                if (key == null || !key.startsWith(AUTO_BACKUP_PREFIX)) {
                    continue;
                }
                String uid = key.substring(AUTO_BACKUP_PREFIX.length());
                if (!uid.isEmpty()) {
                    values.put(uid, cursor.getString(valueIndex));
                }
            }
        } catch (Throwable ignored) {
            return AutoBackup.UNKNOWN;
        }
        if (values.isEmpty()) {
            return AutoBackup.UNKNOWN;
        }
        if (values.size() != 1) {
            return AutoBackup.AMBIGUOUS;
        }
        try {
            return Integer.parseInt(values.values().iterator().next()) > 0
                    ? AutoBackup.ENABLED : AutoBackup.DISABLED;
        } catch (NumberFormatException ignored) {
            return AutoBackup.UNKNOWN;
        }
    }
}
