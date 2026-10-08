package com.ez.zalopatch.xposed.features;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed local fixture. Its result is an OS observation, never a classifier verdict. */
final class NotificationE2eFixture {
    static final String ACTION = "com.ez.zalopatch.behave.NOTIFICATION_E2E";
    private static final String FEATURE = "notifications.observer";
    private static final String[] NAMES = {"dm", "group", "promo", "call", "backup"};
    private static final String[] PREFIXES = {
            "zalo_010_chat_channel_", "zalo_020_chat_group_channel_",
            "zalo_05_social_story_channel_", "zalo_03_call_channel_",
            "zalo_10_db_action_channel_"};
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final String JOURNAL_PREFS = "com.ez.zalopatch.notification_e2e";
    private static final String JOURNAL_KEY = "orphan";

    private NotificationE2eFixture() {
    }

    private static SharedPreferences journalPrefs(Context context) {
        // A scoped file lock serializes the Zalo processes; reload after acquiring it.
        return context.getSharedPreferences(JOURNAL_PREFS,
                Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    /** Recovery is deliberately independent of the debug toggle after an interrupted run. */
    static void cleanupOrphans(Context context) {
        String raw;
        try {
            raw = journalPrefs(context).getString(JOURNAL_KEY, null);
        } catch (Throwable ignored) {
            return;
        }
        if (raw == null || !RUNNING.compareAndSet(false, true)) return;
        String runId = "invalid";
        try {
            JSONObject journal = new JSONObject(raw);
            String recordedRunId = journal.getString("run_id");
            if (!recordedRunId.matches("[a-zA-Z0-9-]{1,64}")) {
                throw new IllegalArgumentException("invalid run id");
            }
            runId = recordedRunId;
            String tag = journal.getString("tag");
            String prefix = "com.ez.zalopatch.e2e:";
            if (!tag.startsWith(prefix)) throw new IllegalArgumentException("invalid tag");
            String token = tag.substring(prefix.length());
            if (!UUID.fromString(token).toString().equals(token)) {
                throw new IllegalArgumentException("invalid tag token");
            }
            JSONArray ids = journal.getJSONArray("ids");
            JSONArray channels = journal.getJSONArray("channels");
            if (ids.length() != NAMES.length || channels.length() != NAMES.length) {
                throw new IllegalArgumentException("invalid journal size");
            }
            Session session = new Session(context, runId, null, false, false, tag, true);
            for (int i = 0; i < NAMES.length; i++) {
                if (ids.getInt(i) != i + 1 || !session.channels[i].equals(channels.getString(i))) {
                    throw new IllegalArgumentException("invalid fixture scope");
                }
            }
            session.journalRaw = raw;
            session.createdChannels.addAll(java.util.Arrays.asList(session.channels));
            start(session);
        } catch (Throwable failure) {
            String error = "journal_invalid_" + failure.getClass().getSimpleName();
            try {
                JSONObject detail = new JSONObject();
                detail.put("run_id", runId);
                detail.put("cleanup_verified", false);
                detail.put("error", error);
                SelfCheckRegistry.markStatus(FEATURE, "unavailable",
                        "e2e:notifications:recovery:" + runId, detail.toString(), error);
            } catch (Throwable ignored) {
            } finally {
                RUNNING.set(false);
            }
        }
    }

    static boolean isDebugCurrentlyEnabled() {
        // HookConfig caches debug at process start; receive-time authorization is live.
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, "debug.zalopatch", "0");
            return "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void receive(Context context, Intent intent, boolean historyEnabledAtHookInstall,
                        boolean callMetadataEnabledAtHookInstall,
                        BroadcastReceiver.PendingResult pending) {
        String runId;
        try {
            runId = intent.getStringExtra("run_id");
        } catch (Throwable ignored) {
            pending.finish();
            return;
        }
        if (runId == null || runId.length() > 64 || !runId.matches("[a-zA-Z0-9-]{1,64}")) {
            pending.finish();
            return;
        }
        Session session = new Session(context, runId, pending, historyEnabledAtHookInstall,
                callMetadataEnabledAtHookInstall, "com.ez.zalopatch.e2e:" + UUID.randomUUID(), false);
        if (!RUNNING.compareAndSet(false, true)) {
            session.error = "fixture_busy";
            session.publish();
            pending.finish();
            return;
        }
        start(session);
    }

    private static void start(Session session) {
        try {
            session.thread.start();
            session.handler = new Handler(session.thread.getLooper());
            session.handler.post(session.recovery ? session::recover : session::post);
        } catch (Throwable failure) {
            session.error = "start_" + failure.getClass().getSimpleName();
            session.finish();
        }
    }

    private static final class Session {
        final Context context;
        final String runId;
        final BroadcastReceiver.PendingResult pending;
        final boolean historyEnabledAtHookInstall;
        final boolean callMetadataEnabledAtHookInstall;
        final boolean recovery;
        final HandlerThread thread = new HandlerThread("ZaloPatch-notification-e2e");
        final String tag;
        final String[] channels = new String[NAMES.length];
        final List<String> createdChannels = new ArrayList<>();
        final List<String> attempted = new ArrayList<>();
        final List<String> posted = new ArrayList<>();
        List<String> active = Collections.emptyList();
        NotificationManager manager;
        Handler handler;
        boolean notificationsEnabled;
        boolean stable;
        boolean cleanupOk;
        boolean cleanupCommandsOk = true;
        boolean cleanupVerified;
        boolean finishing;
        String error = "";
        String cleanupError = "";
        String journalRaw;
        RandomAccessFile lockFile;
        FileLock lock;
        long postedAt;
        long cleanupAt;
        int polls;
        int cleanupPolls;
        int equalSnapshots;

        Session(Context context, String runId, BroadcastReceiver.PendingResult pending,
                boolean historyEnabledAtHookInstall, boolean callMetadataEnabledAtHookInstall,
                String tag, boolean recovery) {
            this.context = context;
            this.runId = runId;
            this.pending = pending;
            this.historyEnabledAtHookInstall = historyEnabledAtHookInstall;
            this.callMetadataEnabledAtHookInstall = callMetadataEnabledAtHookInstall;
            this.tag = tag;
            this.recovery = recovery;
            for (int i = 0; i < channels.length; i++) {
                channels[i] = PREFIXES[i] + "com.ez.zalopatch.e2e_" + tag.substring(tag.indexOf(':') + 1);
            }
        }

        void post() {
            try {
                if (!acquireLock()) {
                    error = "fixture_busy";
                    finish();
                    return;
                }
                if (journalPrefs(context).contains(JOURNAL_KEY)) {
                    error = "orphan_pending";
                    finish();
                    return;
                }
                if (!isDebugCurrentlyEnabled()) {
                    error = "debug_disabled";
                    finish();
                    return;
                }
                if (historyEnabledAtHookInstall
                        || HookConfig.isEnabled(Tweaks.KEY_RECORD_NOTIFICATION_HISTORY)) {
                    error = "history_enabled";
                    finish();
                    return;
                }
                if (callMetadataEnabledAtHookInstall
                        || HookConfig.isEnabled(Tweaks.KEY_AUTO_RECORD_CALLS)) {
                    error = "auto_record_enabled";
                    finish();
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    error = "notification_channels_unavailable";
                    finish();
                    return;
                }
                manager = context.getSystemService(NotificationManager.class);
                if (manager == null || !(notificationsEnabled = manager.areNotificationsEnabled())) {
                    error = manager == null ? "notification_manager_unavailable" : "notifications_blocked";
                    finish();
                    return;
                }
                // Every candidate is absent before committing its recovery ownership.
                for (String channel : channels) {
                    if (manager.getNotificationChannel(channel) != null) {
                        error = "fixture_channel_collision";
                        finish();
                        return;
                    }
                }
                JSONObject journal = new JSONObject();
                journal.put("run_id", runId);
                journal.put("tag", tag);
                JSONArray ids = new JSONArray();
                JSONArray channelIds = new JSONArray();
                for (int i = 0; i < NAMES.length; i++) {
                    ids.put(i + 1);
                    channelIds.put(channels[i]);
                }
                journal.put("ids", ids);
                journal.put("channels", channelIds);
                String raw = journal.toString();
                if (!journalPrefs(context).edit().putString(JOURNAL_KEY, raw).commit()) {
                    error = "journal_write_failed";
                    finish();
                    return;
                }
                journalRaw = raw;
                for (int i = 0; i < NAMES.length; i++) {
                    try {
                        // A random session ID prevents collisions with existing host channels.
                        if (manager.getNotificationChannel(channels[i]) != null) {
                            throw new IllegalStateException("fixture channel collision");
                        }
                        NotificationChannel channel = new NotificationChannel(channels[i],
                                "Zalo Patch test " + NAMES[i], NotificationManager.IMPORTANCE_LOW);
                        channel.setSound(null, null);
                        channel.enableVibration(false);
                        channel.setShowBadge(false);
                        createdChannels.add(channels[i]);
                        manager.createNotificationChannel(channel);
                        Notification notification = new Notification.Builder(context, channels[i])
                                .setSmallIcon(android.R.drawable.stat_notify_more)
                                .setContentTitle(i < 2 ? "Zalo Patch test " + NAMES[i] : "Zalo")
                                .setContentText(i < 2 ? "New message fixture" : "Nang cao an toan voi Zalo")
                                .setCategory(Notification.CATEGORY_MESSAGE)
                                .setOnlyAlertOnce(true)
                                .build();
                        attempted.add(NAMES[i]);
                        manager.notify(tag, i + 1, notification);
                        posted.add(NAMES[i]);
                    } catch (Throwable failure) {
                        recordError("notify_" + NAMES[i] + "_" + failure.getClass().getSimpleName());
                    }
                }
                postedAt = SystemClock.elapsedRealtime();
                handler.postDelayed(this::poll, 250L);
            } catch (Throwable failure) {
                recordError("setup_" + failure.getClass().getSimpleName());
                finish();
            }
        }

        void poll() {
            try {
                boolean[] observed = new boolean[NAMES.length];
                for (StatusBarNotification notification : manager.getActiveNotifications()) {
                    int index = notification.getId() - 1;
                    if (tag.equals(notification.getTag()) && index >= 0 && index < NAMES.length) {
                        observed[index] = true;
                    }
                }
                List<String> snapshot = new ArrayList<>();
                for (int i = 0; i < NAMES.length; i++) {
                    if (observed[i]) snapshot.add(NAMES[i]);
                }
                equalSnapshots = snapshot.equals(active) ? equalSnapshots + 1 : 1;
                active = snapshot;
                polls++;
                long elapsed = SystemClock.elapsedRealtime() - postedAt;
                // Absence is meaningful only after all notify calls and a settle window.
                stable = elapsed >= 1000L && equalSnapshots >= 3;
                if (stable || elapsed >= 3000L) {
                    if (!stable) recordError("snapshot_unstable");
                    notificationsEnabled = manager.areNotificationsEnabled();
                    if (!notificationsEnabled) recordError("notifications_blocked");
                    finish();
                } else {
                    handler.postDelayed(this::poll, 250L);
                }
            } catch (Throwable failure) {
                recordError("snapshot_" + failure.getClass().getSimpleName());
                finish();
            }
        }

        void recordError(String value) {
            if (error.isEmpty()) error = value;
        }

        void recover() {
            try {
                if (!acquireLock()) {
                    error = "fixture_busy";
                } else if (!journalRaw.equals(journalPrefs(context).getString(JOURNAL_KEY, null))) {
                    error = "journal_changed";
                } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    error = "notification_channels_unavailable";
                } else {
                    manager = context.getSystemService(NotificationManager.class);
                    if (manager == null) error = "notification_manager_unavailable";
                }
            } catch (Throwable failure) {
                error = "recovery_" + failure.getClass().getSimpleName();
            } finally {
                finish();
            }
        }

        boolean acquireLock() throws Exception {
            lockFile = new RandomAccessFile(new File(context.getFilesDir(),
                    "com.ez.zalopatch.notification_e2e.lock"), "rw");
            lock = lockFile.getChannel().tryLock();
            return lock != null;
        }

        void finish() {
            if (finishing) return;
            finishing = true;
            try {
                if (manager != null) {
                    for (int i = 0; i < NAMES.length; i++) {
                        try {
                            manager.cancel(tag, i + 1);
                        } catch (Throwable failure) {
                            recordCleanupError("cancel_" + failure.getClass().getSimpleName());
                        }
                    }
                    for (String channel : createdChannels) {
                        try {
                            manager.deleteNotificationChannel(channel);
                        } catch (Throwable failure) {
                            recordCleanupError("channel_cleanup_" + failure.getClass().getSimpleName());
                        }
                    }
                }
            } finally {
                if (manager != null && handler != null) {
                    cleanupAt = SystemClock.elapsedRealtime();
                    handler.postDelayed(this::pollCleanup, 250L);
                } else {
                    complete();
                }
            }
        }

        void recordCleanupError(String value) {
            cleanupCommandsOk = false;
            if (cleanupError.isEmpty()) cleanupError = value;
            recordError(value);
        }

        void pollCleanup() {
            try {
                cleanupPolls++;
                boolean notificationsGone = true;
                for (StatusBarNotification notification : manager.getActiveNotifications()) {
                    int id = notification.getId();
                    if (tag.equals(notification.getTag()) && id >= 1 && id <= NAMES.length) {
                        notificationsGone = false;
                    }
                }
                boolean channelsGone = true;
                for (String channel : createdChannels) {
                    if (manager.getNotificationChannel(channel) != null) channelsGone = false;
                }
                cleanupVerified = notificationsGone && channelsGone;
                if (cleanupVerified) {
                    cleanupOk = cleanupCommandsOk;
                    if (cleanupOk && journalRaw != null) {
                        SharedPreferences prefs = journalPrefs(context);
                        if (!journalRaw.equals(prefs.getString(JOURNAL_KEY, null))
                                || !prefs.edit().remove(JOURNAL_KEY).commit()) {
                            cleanupOk = false;
                            recordCleanupError("journal_clear_failed");
                        }
                    }
                    complete();
                } else if (SystemClock.elapsedRealtime() - cleanupAt >= 2000L) {
                    recordCleanupError("cleanup_timeout");
                    complete();
                } else {
                    handler.postDelayed(this::pollCleanup, 250L);
                }
            } catch (Throwable failure) {
                recordCleanupError("cleanup_snapshot_" + failure.getClass().getSimpleName());
                complete();
            }
        }

        void complete() {
            try {
                publish();
            } finally {
                try {
                    if (lock != null) lock.release();
                    if (lockFile != null) lockFile.close();
                } catch (Throwable ignored) {
                } finally {
                    RUNNING.set(false);
                    thread.quitSafely();
                    if (pending != null) pending.finish();
                }
            }
        }

        void publish() {
            try {
                JSONObject detail = new JSONObject();
                detail.put("run_id", runId);
                detail.put("attempted", new JSONArray(attempted));
                detail.put("attempted_count", attempted.size());
                detail.put("posted", new JSONArray(posted));
                detail.put("active", new JSONArray(active));
                detail.put("notifications_enabled", notificationsEnabled);
                detail.put("poll_count", polls);
                detail.put("stable", stable);
                detail.put("cleanup_ok", cleanupOk);
                detail.put("cleanup_verified", cleanupVerified);
                detail.put("cleanup_poll_count", cleanupPolls);
                detail.put("cleanup_error", cleanupError.isEmpty() ? JSONObject.NULL : cleanupError);
                detail.put("error", error.isEmpty() ? JSONObject.NULL : error);
                SelfCheckRegistry.markStatus(FEATURE, error.isEmpty() ? "active" : "unavailable",
                        "e2e:notifications:" + (recovery ? "recovery:" : "") + runId,
                        detail.toString(), error);
            } catch (Throwable ignored) {
                // Do not log throwable messages or notification payloads.
            }
        }
    }
}
