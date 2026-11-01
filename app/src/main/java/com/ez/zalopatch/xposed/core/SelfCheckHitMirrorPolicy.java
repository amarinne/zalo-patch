package com.ez.zalopatch.xposed.core;

/**
 * Rate-limit decision for coalesced self-check hit reporting. Kept free of Android types so the
 * hot-path timing contract stays unit-testable.
 */
final class SelfCheckHitMirrorPolicy {
    static final long INTERVAL_MS = 1000L;

    private SelfCheckHitMirrorPolicy() {
    }

    /** 0 when a flush may run now, otherwise how long the caller must wait. */
    static long delayMillis(long now, long lastFlushedAt, long intervalMs) {
        if (lastFlushedAt <= 0L || now <= lastFlushedAt) return 0L;
        long elapsed = now - lastFlushedAt;
        return elapsed >= intervalMs ? 0L : intervalMs - elapsed;
    }
}
