package com.ez.zalopatch;

import android.view.animation.Interpolator;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * Shared motion tokens for the module-process UI. Two durations and one interpolator, named
 * here once and reused everywhere so motion stays coherent across pages, bars, and scrims.
 */
final class UiMotion {
    /** Small-surface fades and slides: scrims and the apply bar. */
    static final long SHORT_MS = 150L;
    /** Page-level transitions between settings destinations. */
    static final long PAGE_MS = 220L;
    static final Interpolator FAST_OUT_SLOW_IN = new FastOutSlowInInterpolator();

    private UiMotion() {
    }
}
