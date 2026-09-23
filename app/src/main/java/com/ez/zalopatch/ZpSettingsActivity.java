package com.ez.zalopatch;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/** Shared navigation behavior for settings destinations. */
abstract class ZpSettingsActivity extends AppCompatActivity {
    private View applyBar;
    private TextView applyMessage;
    private View applyButton;
    private View settingsContent;
    private View restartBlocker;
    private boolean restartInFlight;
    private final SharedPreferences.OnSharedPreferenceChangeListener changeListener =
            (preferences, key) -> refreshApplyBar(true);

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ZaloArtifactState.schedule(this);
        TweakStore.initialize(this);
        setContentView(R.layout.zp_settings_activity);
        setSupportActionBar((MaterialToolbar) findViewById(R.id.zp_toolbar));
        applyBar = findViewById(R.id.zp_apply_bar);
        applyMessage = findViewById(R.id.zp_apply_message);
        applyButton = findViewById(R.id.zp_apply_button);
        applyButton.setOnClickListener(view -> restartOrOpenZaloAppInfo());
        settingsContent = findViewById(R.id.zp_settings_content);
        FrameLayout settingsStack = findViewById(R.id.zp_settings_stack);
        restartBlocker = new View(this);
        restartBlocker.setBackgroundColor(Color.argb(51, 0, 0, 0));
        restartBlocker.setAlpha(0f);
        restartBlocker.setClickable(true);
        restartBlocker.setFocusable(true);
        restartBlocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        restartBlocker.setContentDescription(getString(R.string.zp_restart_in_progress));
        restartBlocker.setOnTouchListener((view, event) -> true);
        restartBlocker.setVisibility(View.GONE);
        settingsStack.addView(restartBlocker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshApplyBar(false);
        RootAccess.probeIfNeeded(this, state -> {
            refreshApplyBar(false);
            onRootAccessChanged(state);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        SettingsChanges.preferences(this)
                .registerOnSharedPreferenceChangeListener(changeListener);
        refreshApplyBar(false);
    }

    @Override
    protected void onStop() {
        SettingsChanges.preferences(this)
                .unregisterOnSharedPreferenceChangeListener(changeListener);
        super.onStop();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    final void refreshApplyBar(boolean animate) {
        if (applyBar == null) {
            return;
        }
        int count = SettingsChanges.pendingCount(this);
        applyMessage.setText(getResources().getQuantityString(
                R.plurals.zp_pending_changes, count, count));
        applyButton.setEnabled(!restartInFlight);
        boolean show = count > 0;
        if (show == (applyBar.getVisibility() == View.VISIBLE)) {
            return;
        }
        if (show) {
            applyBar.setVisibility(View.VISIBLE);
            if (animate) {
                applyBar.setAlpha(0f);
                applyBar.setTranslationY(applyBar.getHeight() > 0 ? applyBar.getHeight() : 80f);
                applyBar.animate().alpha(1f).translationY(0f)
                        .setDuration(UiMotion.SHORT_MS)
                        .setInterpolator(UiMotion.FAST_OUT_SLOW_IN).start();
            }
        } else if (animate) {
            applyBar.animate().alpha(0f).translationY(applyBar.getHeight())
                    .setDuration(UiMotion.SHORT_MS)
                    .setInterpolator(UiMotion.FAST_OUT_SLOW_IN)
                    .withEndAction(() -> {
                        applyBar.setVisibility(View.GONE);
                        applyBar.setAlpha(1f);
                        applyBar.setTranslationY(0f);
                    })
                    .start();
        } else {
            applyBar.setVisibility(View.GONE);
        }
    }

    protected final void restartZalo() {
        restartZaloWithTarget(null);
    }

    private void restartZaloWithTarget(ZaloRestart.Target target) {
        if (restartInFlight) {
            return;
        }
        if (RootAccess.cached(this) != RootAccess.State.GRANTED) {
            // Cached denial can outlive a Magisk grant. Re-probe before refusing the action.
            RootAccess.recheck(this, state -> {
                if (state == RootAccess.State.GRANTED) restartZaloWithTarget(target);
                else Toast.makeText(this, R.string.zp_restart_root_denied, Toast.LENGTH_SHORT).show();
            });
            return;
        }
        restartInFlight = true;
        applyButton.setEnabled(false);
        setRestartBlockerVisible(true);
        try {
            onRestartStateChanged(true);
            if (target == null) ZaloRestart.run(this, this::finishRestart);
            else ZaloRestart.run(this, target, this::finishRestart);
        } catch (RuntimeException exception) {
            finishRestart(ZaloRestart.Result.FAILED);
        }
    }

    protected final void restartOrOpenZaloAppInfo() {
        if (RootAccess.cached(this) == RootAccess.State.GRANTED) {
            beginTargetedRestart();
            return;
        }
        // Cached denial can outlive a Magisk grant (open settings first, grant root after).
        // Re-probe on demand before falling back to manual force-stop; root is demanded
        // rarely so the extra `su` probe here is cheap.
        RootAccess.recheck(this, state -> {
            refreshApplyBar(false);
            onRootAccessChanged(state);
            if (state == RootAccess.State.GRANTED) beginTargetedRestart();
            else openZaloAppInfo();
        });
    }

    /**
     * Probes running Zalo instances before restarting. One instance (or none, or a failed
     * probe) restarts exactly as before; two or more get an instance selector with a
     * restart-all row, since an unqualified force-stop would kill the clones without
     * relaunching them.
     */
    private void beginTargetedRestart() {
        if (restartInFlight) {
            return;
        }
        restartInFlight = true;
        applyButton.setEnabled(false);
        setRestartBlockerVisible(true);
        onRestartStateChanged(true);
        new Thread(() -> {
            List<ZaloInstance> instances =
                    ZaloRestart.probeInstances(new DiagnosticRootProcessRunner());
            new Handler(Looper.getMainLooper()).post(() -> onInstancesProbed(instances));
        }, "zalo-instance-probe").start();
    }

    private void onInstancesProbed(List<ZaloInstance> instances) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        restartInFlight = false;
        setRestartBlockerVisible(false);
        onRestartStateChanged(false);
        if (!ZaloInstance.needsSelection(instances)) {
            restartZalo();
            return;
        }
        showRestartTargetDialog(instances);
    }

    private void showRestartTargetDialog(List<ZaloInstance> instances) {
        CharSequence[] labels = new CharSequence[instances.size() + 1];
        for (int index = 0; index < instances.size(); index++) {
            labels[index] = instanceLabel(instances.get(index));
        }
        labels[instances.size()] = getString(R.string.zp_restart_all_instances);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zp_restart_target_title)
                .setItems(labels, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == instances.size()) {
                        restartZaloWithTarget(
                                ZaloRestart.Target.all(ZaloInstance.userIds(instances)));
                    } else {
                        restartZaloWithTarget(
                                ZaloRestart.Target.single(instances.get(which).userId));
                    }
                })
                .setNegativeButton(R.string.zp_cancel, null)
                .show();
    }

    private String instanceLabel(ZaloInstance instance) {
        String name = instance.label != null ? instance.label
                : instance.userId == 0 ? getString(R.string.zp_restart_owner_label)
                : getString(R.string.zp_restart_user_label, instance.userId);
        if (instance.foreground) {
            return getString(R.string.zp_restart_instance_row_active,
                    name, instance.userId, instance.mainPid(),
                    getString(R.string.zp_restart_instance_active));
        }
        return getString(R.string.zp_restart_instance_row,
                name, instance.userId, instance.mainPid());
    }

    private void finishRestart(ZaloRestart.Result result) {
        restartInFlight = false;
        setRestartBlockerVisible(false);
        onRestartStateChanged(false);
        int message = result == ZaloRestart.Result.SENT
                ? R.string.zp_restart_sent
                : result == ZaloRestart.Result.ROOT_DENIED
                ? R.string.zp_restart_root_denied
                : R.string.zp_restart_failed;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        refreshApplyBar(true);
        onRestartResult(result);
    }

    private void setRestartBlockerVisible(boolean visible) {
        settingsContent.setImportantForAccessibility(visible
                ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        // Fade the scrim instead of popping it. The end action refuses to hide the view when a
        // restart started again mid-fade, so a quick cancel/restart cycle cannot blank it.
        if (visible) {
            restartBlocker.setVisibility(View.VISIBLE);
            restartBlocker.animate().alpha(1f).setDuration(UiMotion.SHORT_MS)
                    .setInterpolator(UiMotion.FAST_OUT_SLOW_IN).start();
            restartBlocker.announceForAccessibility(restartBlocker.getContentDescription());
        } else {
            restartBlocker.animate().alpha(0f).setDuration(UiMotion.SHORT_MS)
                    .setInterpolator(UiMotion.FAST_OUT_SLOW_IN)
                    .withEndAction(() -> {
                        if (!restartInFlight) {
                            restartBlocker.setVisibility(View.GONE);
                        }
                    })
                    .start();
        }
    }

    protected void onRestartResult(ZaloRestart.Result result) {
    }

    protected void onRestartStateChanged(boolean inFlight) {
    }

    protected void onRootAccessChanged(RootAccess.State state) {
    }

    protected final void recheckRootAccess() {
        RootAccess.recheck(this, state -> {
            refreshApplyBar(false);
            onRootAccessChanged(state);
        });
    }

    protected final void openZaloAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:com.zing.zalo")));
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.zp_open_zalo_app_info_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }
}
