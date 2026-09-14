package com.ez.zalopatch;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * JVM tests for the cache file lock (finding 5): concurrent reads, writes, and deletes
 * through one shared lock never interleave.
 */
public final class DexKitStoreLockTest {
    @Test
    public void concurrentFileOperationsNeverInterleave() throws Exception {
        final int threads = 8;
        final int operations = 50;
        final FakeIo io = new FakeIo();
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<Throwable>());
        for (int index = 0; index < threads; index++) {
            final int slot = index;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await();
                        for (int step = 0; step < operations; step++) {
                            int kind = (slot + step) % 3;
                            if (kind == 0) {
                                DexKitStore.loadLocked(io);
                            } else if (kind == 1) {
                                DexKitStore.saveLocked(io, null,
                                        "{\"version_code\":260802903,"
                                                + "\"code_digest\":\"5736dd272918b73d689d11ff62e9399d80effaad85b30f2180b24ab8809a09ed\","
                                                + "\"last_update_time\":7,\"apk_size\":68157440,"
                                                + "\"query_revision\":1,\"resolver_format\":2,"
                                                + "\"module_version\":204,\"ad_bind\":\"c\","
                                                + "\"feed_bind\":\"c\",\"negative\":false,\"reason\":\"\","
                                                + "\"match_ad\":1,\"match_feed\":1,"
                                                + "\"scan_duration_ms\":1,\"scanned_at\":1}");
                            } else {
                                DexKitStore.clearLocked(io);
                            }
                        }
                    } catch (Throwable throwable) {
                        failures.add(throwable);
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(1, io.maxActive.get());
    }

    /** Records the maximum number of threads simultaneously inside a file operation. */
    private static final class FakeIo implements DexKitStore.StoreIo {
        final AtomicInteger active = new AtomicInteger();
        final AtomicInteger maxActive = new AtomicInteger();
        byte[] data;

        private void enter() {
            int current = active.incrementAndGet();
            int max;
            do {
                max = maxActive.get();
            } while (current > max && !maxActive.compareAndSet(max, current));
            try {
                Thread.sleep(0, 1000);
            } catch (InterruptedException ignored) {
            }
        }

        private void exit() {
            active.decrementAndGet();
        }

        @Override
        public byte[] read() {
            enter();
            try {
                if (data == null) {
                    throw new IllegalStateException("absent");
                }
                return data.clone();
            } finally {
                exit();
            }
        }

        @Override
        public void write(byte[] value) {
            enter();
            try {
                data = value.clone();
            } finally {
                exit();
            }
        }

        @Override
        public void delete() {
            enter();
            try {
                data = null;
            } finally {
                exit();
            }
        }

        @Override
        public boolean exists() {
            return data != null;
        }
    }
}
