package com.ez.zalopatch;

import android.os.Bundle;
import android.graphics.Color;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

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
        applyButton.setOnClickListener(view -> restartZalo());
        settingsContent = findViewById(R.id.zp_settings_content);
        FrameLayout settingsStack = findViewById(R.id.zp_settings_stack);
        restartBlocker = new View(this);
        restartBlocker.setBackgroundColor(Color.argb(51, 0, 0, 0));
        restartBlocker.setClickable(true);
        restartBlocker.setFocusable(true);
        restartBlocker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        restartBlocker.setContentDescription(getString(R.string.zp_restart_in_progress));
        restartBlocker.setOnTouchListener((view, event) -> true);
        restartBlocker.setVisibility(View.GONE);
        settingsStack.addView(restartBlocker, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        refreshApplyBar(false);
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

    private void refreshApplyBar(boolean animate) {
        if (applyBar == null) {
            return;
        }
        int count = SettingsChanges.pendingCount(this);
        applyMessage.setText(getResources().getQuantityString(
                R.plurals.zp_pending_changes, count, count));
        boolean show = count > 0;
        if (show == (applyBar.getVisibility() == View.VISIBLE)) {
            return;
        }
        if (show) {
            applyBar.setVisibility(View.VISIBLE);
            if (animate) {
                applyBar.setAlpha(0f);
                applyBar.setTranslationY(applyBar.getHeight() > 0 ? applyBar.getHeight() : 80f);
                applyBar.animate().alpha(1f).translationY(0f).setDuration(180L).start();
            }
        } else if (animate) {
            applyBar.animate().alpha(0f).translationY(applyBar.getHeight())
                    .setDuration(160L)
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
        if (restartInFlight) {
            return;
        }
        restartInFlight = true;
        applyButton.setEnabled(false);
        setRestartBlockerVisible(true);
        try {
            onRestartStateChanged(true);
            ZaloRestart.run(this, this::finishRestart);
        } catch (RuntimeException exception) {
            finishRestart(ZaloRestart.Result.FAILED);
        }
    }

    private void finishRestart(ZaloRestart.Result result) {
        restartInFlight = false;
        applyButton.setEnabled(true);
        setRestartBlockerVisible(false);
        onRestartStateChanged(false);
        int message = result == ZaloRestart.Result.SENT
                ? R.string.zp_restart_sent
                : result == ZaloRestart.Result.SENT_NO_ROOT
                ? R.string.zp_restart_sent_no_root
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
        restartBlocker.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            restartBlocker.announceForAccessibility(restartBlocker.getContentDescription());
        }
    }

    protected void onRestartResult(ZaloRestart.Result result) {
    }

    protected void onRestartStateChanged(boolean inFlight) {
    }
}
