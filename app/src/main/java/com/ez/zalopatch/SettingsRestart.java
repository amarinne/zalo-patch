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

    void start(Context context) {
        if (state.getValue().phase != Phase.IDLE) return;
        application = context.getApplicationContext();
        long token = ++operation;
        state.setValue(new State(Phase.ROOT, null, null));
        if (RootAccess.cached(application) == RootAccess.State.GRANTED) rootReady(token, RootAccess.State.GRANTED);
        else RootAccess.recheck(application, root -> rootReady(token, root));
    }

    private void rootReady(long token, RootAccess.State root) {
        if (token != operation || state.getValue().phase != Phase.ROOT) return;
        if (root != RootAccess.State.GRANTED) {
            state.setValue(new State(Phase.RESTART, null, null));
            new Thread(() -> {
                boolean published = FrameworkConfig.apply(application);
                main.post(() -> done(token, published ? ZaloRestart.Result.MANUAL : ZaloRestart.Result.FAILED));
            }, "settings-manual-restart").start();
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
        if (state.getValue().phase == Phase.SELECT) run(operation, target);
    }

    void cancelSelection() {
        if (state.getValue().phase == Phase.SELECT) state.setValue(new State(Phase.IDLE, null, null));
    }

    void consumeResult() {
        if (state.getValue().phase == Phase.DONE) state.setValue(new State(Phase.IDLE, null, null));
    }

    private void run(long token, ZaloRestart.Target target) {
        state.setValue(new State(Phase.RESTART, null, null));
        ZaloRestart.run(application, target, result -> done(token, result));
    }

    private void done(long token, ZaloRestart.Result result) {
        if (token == operation && state.getValue().phase == Phase.RESTART) {
            state.setValue(new State(Phase.DONE, null, result));
        }
    }
}
