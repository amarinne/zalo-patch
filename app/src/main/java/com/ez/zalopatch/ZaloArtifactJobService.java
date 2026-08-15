package com.ez.zalopatch;

import android.app.job.JobParameters;
import android.app.job.JobService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ZaloArtifactJobService extends JobService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private volatile Future<?> active;
    private volatile int token;

    @Override
    public boolean onStartJob(JobParameters params) {
        int current = ++token;
        active = EXECUTOR.submit(() -> {
            ZaloArtifactState.reconcile(getApplicationContext());
            if (token == current) {
                jobFinished(params, false);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        token++;
        Future<?> current = active;
        if (current != null) {
            current.cancel(true);
        }
        return false;
    }
}
