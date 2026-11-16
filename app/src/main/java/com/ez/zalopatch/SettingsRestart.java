package com.ez.zalopatch;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.util.Collections;
import java.util.List;

/** Retains one user action across activity recreation; workers never retain an activity. */
public final class SettingsRestart extends ViewModel {
    enum Phase { IDLE, ROOT, PROBE, SELECT, RESTART, DONE }
    static final class State {
        final Phase phase;
        final List<ZaloInstance> instances;
        final ZaloRestart.Result result;
        State(Phase phase, List<ZaloInstance> instances, ZaloRestart.Result result) {
            this.phase = phase;
            this.instances = instances;
            this.result = result;
        }
    }

    final MutableLiveData<State> state = new MutableLiveData<>(new State(Phase.IDLE, null, null));
    private final Handler main = new Handler(Looper.getMainLooper());
    private long operation;
    private Context application;
    /** Captured at start; selection changes never rewrite an in-flight package target. */
    private ZaloRestart.InstanceTarget scopedTarget;

    void start(Context context) {
        if (state.getValue().phase != Phase.IDLE) return;
        begin(context, null);
    }

    /** Package-aware same-user action for a selected registered target. */
    void start(Context context, String packageName) {
        if (state.getValue().phase != Phase.IDLE) return;
        ZaloRestart.InstanceTarget captured = ZaloRestart.captureTarget(context, packageName);
        if (captured == null) {
            scopedTarget = null;
            state.setValue(new State(Phase.DONE, null, ZaloRestart.Result.FAILED));
            return;
        }
        begin(context, captured);
    }

    private void begin(Context context, ZaloRestart.InstanceTarget captured) {
        Context app = context.getApplicationContext();
        application = app == null ? context : app;
        scopedTarget = captured;
        long token = ++operation;
        state.setValue(new State(Phase.ROOT, null, null));
        if (RootAccess.cached(application) == RootAccess.State.GRANTED) rootReady(token, RootAccess.State.GRANTED);
        else RootAccess.recheck(application, root -> rootReady(token, root));
    }

    private void rootReady(long token, RootAccess.State root) {
        if (token != operation || state.getValue().phase != Phase.ROOT) return;
        if (root != RootAccess.State.GRANTED) {
            state.setValue(new State(Phase.RESTART, null, null));
            Context manualContext = application;
            ZaloRestart.InstanceTarget captured = scopedTarget;
            new Thread(() -> {
                boolean published = captured == null
                        ? FrameworkConfig.apply(manualContext)
                        : captured.valid() && FrameworkConfig.apply(captured.context());
                main.post(() -> done(token, published ? ZaloRestart.Result.MANUAL : ZaloRestart.Result.FAILED));
            }, "settings-manual-restart").start();
            return;
        }
        if (scopedTarget != null) {
            // A selected target is restarted directly: a stopped clone has no pid to probe, and
            // probe failure must never fall back to the canonical package.
            run(token, null);
            return;
        }
        state.setValue(new State(Phase.PROBE, null, null));
        new Thread(() -> {
            List<ZaloInstance> detected;
            try { detected = ZaloRestart.probeInstances(new DiagnosticRootProcessRunner()); }
            catch (RuntimeException failure) { detected = Collections.emptyList(); }
            List<ZaloInstance> instances = detected;
            main.post(() -> {
                if (token != operation || state.getValue().phase != Phase.PROBE) return;
                if (ZaloInstance.needsSelection(instances)) state.setValue(new State(Phase.SELECT, instances, null));
                else run(token, null);
            });
        }, "zalo-instance-probe").start();
    }

    void select(ZaloRestart.Target target) {
        if (scopedTarget != null) return;
        if (state.getValue().phase == Phase.SELECT) run(operation, target);
    }

    void cancelSelection() {
        if (state.getValue().phase == Phase.SELECT) {
            scopedTarget = null;
            state.setValue(new State(Phase.IDLE, null, null));
        }
    }

    void consumeResult() {
        if (state.getValue().phase == Phase.DONE) {
            scopedTarget = null;
            state.setValue(new State(Phase.IDLE, null, null));
        }
    }

    private void run(long token, ZaloRestart.Target target) {
        state.setValue(new State(Phase.RESTART, null, null));
        ZaloRestart.InstanceTarget captured = scopedTarget;
        if (captured != null) {
            ZaloRestart.runScoped(captured, result -> done(token, result));
        } else {
            ZaloRestart.run(application, target, result -> done(token, result));
        }
    }

    private void done(long token, ZaloRestart.Result result) {
        if (token == operation && state.getValue().phase == Phase.RESTART) {
            state.setValue(new State(Phase.DONE, null, result));
        }
    }

    @Override protected void onCleared() {
        operation++;
        main.removeCallbacksAndMessages(null);
        application = null;
        scopedTarget = null;
    }
}
