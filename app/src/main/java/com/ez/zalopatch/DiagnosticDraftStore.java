package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Exact pending payload retained briefly for review and a manual retry. */
final class DiagnosticDraftStore {
    private static final String PREFS = "diagnostic_draft_v1";
    private static final String FILE_NAME = "zalo-patch-diagnostic-pending-v1.json";

    private DiagnosticDraftStore() {
    }

    static boolean save(Context context, DiagnosticReportFactory.Draft draft) {
        if (draft == null || !DiagnosticReportContract.validDraftJson(draft.json)) return false;
        File file = new File(context.getCacheDir(), FILE_NAME);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(draft.json.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        } catch (Exception ignored) {
            clear(context);
            return false;
        }
        return preferences(context).edit()
                .putLong("created_at", System.currentTimeMillis())
                .putString("report_id", draft.reportId)
                .commit();
    }

    static DiagnosticReportFactory.Draft load(Context context) {
        SharedPreferences prefs = preferences(context);
        long createdAt = prefs.getLong("created_at", -1L);
        long now = System.currentTimeMillis();
        if (createdAt < 0L || now < createdAt
                || now - createdAt >= DiagnosticReportContract.TTL_MS) {
            clear(context);
            return null;
        }
        File file = new File(context.getCacheDir(), FILE_NAME);
        try {
            if (!file.exists() || file.length() <= 0L
                    || file.length() > DiagnosticReportContract.CLIENT_BODY_BYTES) {
                clear(context);
                return null;
            }
            String json = read(file);
            DiagnosticReportFactory.Draft draft = DiagnosticReportFactory.fromJson(json);
            if (draft == null
                    || !draft.reportId.equals(prefs.getString("report_id", ""))) {
                clear(context);
                return null;
            }
            return draft;
        } catch (Exception ignored) {
            clear(context);
            return null;
        }
    }

    static void clear(Context context) {
        new File(context.getCacheDir(), FILE_NAME).delete();
        preferences(context).edit().clear().commit();
    }

    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > DiagnosticReportContract.CLIENT_BODY_BYTES) {
                    throw new IllegalArgumentException("draft too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
