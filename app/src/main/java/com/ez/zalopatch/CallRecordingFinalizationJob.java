package com.ez.zalopatch;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.job.JobWorkItem;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Runs in a dedicated process so blocked codec and provider calls can be terminated safely. */
public final class CallRecordingFinalizationJob extends JobService {
    static final String PROCESS_SUFFIX = ":call_recording";
    private static final int JOB_ID = 0x5a200000;
    private volatile JobParameters active;

    static boolean isScheduled(Context context) {
        return context.getSystemService(JobScheduler.class).getPendingJob(JOB_ID) != null;
    }

    static boolean enqueue(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, CallRecordingFinalizationJob.class))
                .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_LINEAR);
        // A constraint-free regular job waits for batching (up to 31 min on Android 12)
        // and for Doze maintenance. Expedited jobs start promptly after a call.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setExpedited(true);
        JobInfo job = builder.build();
        try {
            // Work items are only wakeups. The private queue owns recordings and their ordering.
            // Enqueue atomically with JobScheduler's empty-queue check, including during a run.
            return context.getSystemService(JobScheduler.class)
                    .enqueue(job, new JobWorkItem(null)) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        active = params;
        new Thread(() -> drain(params), "call-recording-finalization").start();
        return true;
    }

    private void drain(JobParameters params) {
        CallRecordingFinalization.Watchdog deadline =
                new CallRecordingFinalization.Watchdog(SystemClock.elapsedRealtime());
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
        Runnable progress = () -> deadline.progress(SystemClock.elapsedRealtime());
        watchdog.scheduleWithFixedDelay(() -> {
            if (active == params && deadline.expired(SystemClock.elapsedRealtime())) {
                // Interrupting the encoder thread cannot stop a hung native or Binder call.
                // JobScheduler redelivers executing work after an unexpected disconnect.
                Process.killProcess(Process.myPid());
            }
        }, 1L, 1L, TimeUnit.SECONDS);
        try {
            JobWorkItem wakeup;
            while ((wakeup = params.dequeueWork()) != null) {
                while (CallRecordingStore.finalizeNext(this, progress)) progress.run();
                params.completeWork(wakeup);
                progress.run();
            }
        } catch (RuntimeException failure) {
            if (active == params) Process.killProcess(Process.myPid());
        } finally {
            if (active == params) active = null;
            watchdog.shutdownNow();
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (active != null) Process.killProcess(Process.myPid());
        return true;
    }
}
