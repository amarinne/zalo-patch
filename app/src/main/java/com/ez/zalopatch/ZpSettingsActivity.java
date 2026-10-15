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
import android.content.Context;
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
    private boolean restartBlocking;
    private SettingsRestart restart;
    private androidx.appcompat.app.AlertDialog targetDialog;
    private final SharedPreferences.OnSharedPreferenceChangeListener changeListener =
            (preferences, key) -> {
                if (preferences == SettingsChanges.preferences(this) || SettingsStore.GENERATION.equals(key)
                        || ConfigSnapshot.KEY.equals(key)) refreshApplyBar(true);
            };

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
        onRootAccessChanged(RootAccess.cached(this));
        restart = new androidx.lifecycle.ViewModelProvider(this).get(SettingsRestart.class);
        restart.state.observe(this, this::renderRestart);
    }

    @Override
    protected void onStart() {
        super.onStart();
        SettingsChanges.preferences(this).registerOnSharedPreferenceChangeListener(changeListener);
        TweakStore.preferences(this).registerOnSharedPreferenceChangeListener(changeListener);
        refreshApplyBar(false);
    }

    @Override
    protected void onStop() {
        SettingsChanges.preferences(this).unregisterOnSharedPreferenceChangeListener(changeListener);
        TweakStore.preferences(this).unregisterOnSharedPreferenceChangeListener(changeListener);
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
        boolean prepare = SettingsChanges.active(this) == null
                || SettingsChanges.active(this).moduleVersion != BuildConfig.VERSION_CODE;
        applyMessage.setText(prepare ? getString(R.string.zp_config_prepare_required)
                : getResources().getQuantityString(R.plurals.zp_pending_changes, count, count));
        applyButton.setEnabled(!restartInFlight);
        boolean show = count > 0 || prepare;
        applyBar.animate().cancel();
        if (show == (applyBar.getVisibility() == View.VISIBLE)) {
            applyBar.setAlpha(1f);
            applyBar.setTranslationY(0f);
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
        restartOrOpenZaloAppInfo();
    }

    protected final void restartOrOpenZaloAppInfo() {
        if (!isFinishing() && !isDestroyed()) restart.start(this);
    }

    private void renderRestart(SettingsRestart.State state) {
        boolean busy = state.phase != SettingsRestart.Phase.IDLE
                && state.phase != SettingsRestart.Phase.DONE;
        restartInFlight = busy;
        applyButton.setEnabled(!busy);
        setRestartBlockerVisible(busy && state.phase != SettingsRestart.Phase.SELECT);
        onRestartStateChanged(busy);
        if (state.phase == SettingsRestart.Phase.SELECT && targetDialog == null) {
            showRestartTargetDialog(state.instances);
        }
        if (state.phase == SettingsRestart.Phase.DONE) {
            restart.consumeResult();
            finishRestart(state.result);
        }
    }

    // The root status continuation does not itself authorize an App info launch.
    final void finishRestartRootCheck(RootAccess.State state) {
        if (isFinishing() || isDestroyed()) return;
        refreshApplyBar(false);
        onRootAccessChanged(state);
    }

    private void showRestartTargetDialog(List<ZaloInstance> instances) {
        CharSequence[] labels = new CharSequence[instances.size() + 1];
        for (int index = 0; index < instances.size(); index++) labels[index] = instanceLabel(instances.get(index));
        labels[instances.size()] = getString(R.string.zp_restart_all_instances);
        targetDialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.zp_restart_target_title)
                .setItems(labels, (dialog, which) -> restart.select(which == instances.size()
                        ? ZaloRestart.Target.all(ZaloInstance.userIds(instances))
                        : ZaloRestart.Target.single(instances.get(which).userId)))
                .setNegativeButton(R.string.zp_cancel, (dialog, which) -> restart.cancelSelection())
                .setOnCancelListener(dialog -> restart.cancelSelection())
                .create();
        targetDialog.setOnDismissListener(dialog -> targetDialog = null);
        targetDialog.show();
    }

    @Override protected void onDestroy() {
        if (targetDialog != null) targetDialog.dismiss();
        super.onDestroy();
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
                : result == ZaloRestart.Result.MANUAL ? R.string.zp_config_manual_restart
                : result == ZaloRestart.Result.ROOT_DENIED
                ? R.string.zp_restart_root_denied
                : R.string.zp_restart_failed;
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, message, result == ZaloRestart.Result.MANUAL
                ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
        refreshApplyBar(true);
        onRestartResult(result);
    }

    private void setRestartBlockerVisible(boolean visible) {
        restartBlocking = visible;
        restartBlocker.animate().cancel();
        ((ViewGroup) settingsContent).setDescendantFocusability(visible
                ? ViewGroup.FOCUS_BLOCK_DESCENDANTS : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (visible) {
            settingsContent.clearFocus();
            restartBlocker.requestFocus();
        }
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
                        if (!restartBlocking) {
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
