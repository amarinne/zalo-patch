package com.ez.zalopatch;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs artifact reconciliation for the explicit target retained in the job extras. The single
 * executor plus synchronized reconcile serializes writers; each job tracks its own generation so
 * stopping one target never cancels another target's queued work.
 */
public final class ZaloArtifactJobService extends JobService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Map<Integer, Future<?>> active = new HashMap<>();
    private final Map<Integer, Long> generations = new HashMap<>();
    private long clock;

    @Override
    public boolean onStartJob(JobParameters params) {
        Context application = getApplicationContext();
        String requested = params.getExtras() == null
                ? null : params.getExtras().getString(ZaloArtifactState.JOB_HOST_PACKAGE);
        // Missing, malformed or revoked selected target is invalid; never default to original.
        if (ZaloArtifactState.jobTarget(application, requested) == null) {
            return false;
        }
        final int jobId = params.getJobId();
        final long generation;
        synchronized (this) {
            generation = ++clock;
            generations.put(jobId, generation);
        }
        Future<?> future = EXECUTOR.submit(() -> run(params, jobId, generation));
        synchronized (this) {
            if (current(jobId, generation)) {
                active.put(jobId, future);
            }
        }
        return true;
    }

    private void run(JobParameters params, int jobId, long generation) {
        try {
            if (!current(jobId, generation)) return;
            Context application = getApplicationContext();
            String requested = params.getExtras() == null
                    ? null : params.getExtras().getString(ZaloArtifactState.JOB_HOST_PACKAGE);
            // Registration may have been revoked after scheduling. Revalidate the stored target
            // and keep the scoped context; never fall back to the base original context.
            Context scoped = ZaloArtifactState.jobContext(application, requested);
            if (scoped == null || !current(jobId, generation)) return;
            ZaloArtifactState.reconcile(scoped);
        } finally {
            boolean finish;
            synchronized (this) {
                finish = current(jobId, generation);
                if (finish) {
                    generations.remove(jobId);
                    active.remove(jobId);
                }
            }
            if (finish) jobFinished(params, false);
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        int jobId = params.getJobId();
        Future<?> current;
        synchronized (this) {
            generations.remove(jobId);
            current = active.remove(jobId);
        }
        if (current != null) {
            current.cancel(true);
        }
        return false;
    }

    private synchronized boolean current(int jobId, long generation) {
        Long stored = generations.get(jobId);
        return stored != null && stored == generation;
    }
}
