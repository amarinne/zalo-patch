package com.ez.zalopatch;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SelfCheckData {
    static final Uri URI = Uri.parse("content://com.ez.zalopatch.config/self_check");

    private SelfCheckData() {
    }

    static List<Row> load(Context context) {
        ArrayList<Row> rows = new ArrayList<>();
        Cursor cursor = context.getContentResolver().query(URI, null, null, null, null);
        if (cursor == null) {
            return rows;
        }
        try {
            while (cursor.moveToNext()) {
                rows.add(new Row(
                        value(cursor, "feature"),
                        value(cursor, "status"),
                        intValue(cursor, "install_count"),
                        intValue(cursor, "hit_count"),
                        value(cursor, "target"),
                        value(cursor, "detail"),
                        value(cursor, "error"),
                        longValue(cursor, "updated_at"),
                        value(cursor, "artifact_generation"),
                        value(cursor, "run_id")));
            }
        } finally {
            cursor.close();
        }
        return rows;
    }

    static Map<String, Row> byFeature(List<Row> rows) {
        HashMap<String, Row> map = new HashMap<>();
        for (Row row : rows) {
            map.put(row.feature, row);
        }
        return map;
    }

    static Counts counts(List<Row> rows) {
        Counts counts = new Counts();
        for (Row row : rows) {
            if ("failed".equals(row.status)) {
                counts.failed++;
            } else if ("stale".equals(row.status)) {
                counts.stale++;
            } else if ("installed_no_hits".equals(row.status)) {
                counts.installedNoHits++;
            } else if ("active".equals(row.status)) {
                counts.active++;
            } else if ("disabled".equals(row.status)) {
                counts.disabled++;
            } else {
                counts.other++;
            }
        }
        return counts;
    }

    static String formatUpdatedAt(Context context, long updatedAt) {
        if (updatedAt <= 0L) {
            return context.getString(R.string.zp_never);
        }
        return DateFormat.getMediumDateFormat(context).format(updatedAt)
                + " "
                + DateFormat.getTimeFormat(context).format(updatedAt);
    }

    static String statusTitle(Context context, String status) {
        if ("installed_no_hits".equals(status)) {
            return context.getString(R.string.zp_state_installed_no_hits);
        }
        if ("failed".equals(status)) {
            return context.getString(R.string.zp_state_failed);
        }
        if ("stale".equals(status)) {
            return context.getString(R.string.zp_state_stale);
        }
        if ("active".equals(status)) {
            return context.getString(R.string.zp_state_active);
        }
        if ("disabled".equals(status)) {
            return context.getString(R.string.zp_state_disabled);
        }
        return context.getString(R.string.zp_state_unknown);
    }

    static String representativeStatus(
            int failed, int stale, int active, int installedNoHits, int disabled) {
        if (failed > 0) return "failed";
        if (stale > 0) return "stale";
        if (active > 0) return "active";
        if (installedNoHits > 0) return "installed_no_hits";
        if (disabled > 0) return "disabled";
        return "";
    }

    static List<Row> rowsForStatus(List<Row> rows, String status) {
        ArrayList<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (status.equals(row.status)) {
                result.add(row);
            }
        }
        return result;
    }

    private static String value(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? "" : cursor.getString(index);
    }

    private static int intValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0 : cursor.getInt(index);
    }

    private static long longValue(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? 0L : cursor.getLong(index);
    }

    static final class Row {
        final String feature;
        final String status;
        final int installCount;
        final int hitCount;
        final String target;
        final String detail;
        final String error;
        final long updatedAt;
        final String artifactGeneration;
        final String runId;

        Row(String feature, String status, int installCount, int hitCount, String target,
            String detail, String error, long updatedAt, String artifactGeneration, String runId) {
            this.feature = feature;
            this.status = status;
            this.installCount = installCount;
            this.hitCount = hitCount;
            this.target = target;
            this.detail = detail;
            this.error = error;
            this.updatedAt = updatedAt;
            this.artifactGeneration = artifactGeneration;
            this.runId = runId;
        }

        String compactSummary(Context context) {
            StringBuilder summary = new StringBuilder();
            summary.append(statusTitle(context, status));
            summary.append(" · ").append(context.getString(R.string.zp_hits_value, hitCount));
            if (!target.isEmpty()) {
                summary.append(" · ").append(target);
            }
            if (!detail.isEmpty()) {
                summary.append("\n").append(detail);
            }
            if (!error.isEmpty()) {
                summary.append("\n").append(error);
            }
            summary.append("\n").append(context.getString(
                    R.string.zp_updated_value, formatUpdatedAt(context, updatedAt)));
            return summary.toString();
        }

        String listSummary(Context context) {
            StringBuilder summary = new StringBuilder();
            summary.append(context.getString(R.string.zp_hits_value, hitCount));
            if (!target.isEmpty()) {
                summary.append(" · ").append(target);
            }
            if (updatedAt > 0L) {
                summary.append(" · ").append(formatUpdatedAt(context, updatedAt));
            }
            return summary.toString();
        }

        String debugDetails(Context context) {
            StringBuilder details = new StringBuilder();
            details.append(context.getString(R.string.zp_feature_value, feature));
            details.append("\n").append(context.getString(
                    R.string.zp_status_value, statusTitle(context, status)));
            details.append("\n").append(context.getString(R.string.zp_installed_value, installCount));
            details.append("\n").append(context.getString(R.string.zp_hits_label_value, hitCount));
            if (!target.isEmpty()) {
                details.append("\n").append(context.getString(R.string.zp_target_value, target));
            }
            if (!detail.isEmpty()) {
                details.append("\n").append(context.getString(R.string.zp_detail_value, detail));
            }
            if (!error.isEmpty()) {
                details.append("\n").append(context.getString(R.string.zp_error_value, error));
            }
            details.append("\n").append(context.getString(
                    R.string.zp_updated_value, formatUpdatedAt(context, updatedAt)));
            if (!artifactGeneration.isEmpty()) {
                details.append("\nArtifact: ").append(shortId(artifactGeneration));
            }
            if (!runId.isEmpty()) {
                details.append("\nRun: ").append(shortId(runId));
            }
            return details.toString();
        }

        private static String shortId(String value) {
            return value.length() <= 12 ? value : value.substring(0, 12);
        }
    }

    static final class Counts {
        int failed;
        int stale;
        int installedNoHits;
        int active;
        int disabled;
        int other;

        int total() {
            return failed + stale + installedNoHits + active + disabled + other;
        }
    }
}
