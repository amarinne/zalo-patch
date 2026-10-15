package com.ez.zalopatch.xposed.core;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;

import org.json.JSONObject;

/** Two delayed observations of a named feature surface, never a global view scan. */
public final class UiOutcomeObserver {
    public interface CurrentMarker { boolean present(); }
    public interface Sink { void contradiction(String feature, String route, String evidence); }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Sink sink;
    private boolean pending;
    private boolean reported;
    private long lastStarted = -5000;

    public UiOutcomeObserver() { this(SelfCheckRegistry::markOutcomeContradiction); }
    public UiOutcomeObserver(Sink sink) { this.sink = sink; }

    /** Call only at a feature's bind/inflate edge, with a current-binding predicate. */
    public void afterBind(String feature, String route, View view, CurrentMarker marker) {
        if (Looper.myLooper() != Looper.getMainLooper() || view == null || reported || pending
                || SystemClock.elapsedRealtime() - lastStarted < 5000) return;
        pending = true;
        lastStarted = SystemClock.elapsedRealtime();
        // Work and retained references last at most one second. No listeners or per-frame IPC.
        handler.postDelayed(() -> {
            if (!visible(view, marker)) { pending = false; return; }
            handler.postDelayed(() -> {
                pending = false;
                if (!visible(view, marker)) return;
                try {
                    Rect bounds = new Rect();
                    if (!view.getGlobalVisibleRect(bounds) || bounds.isEmpty()) return;
                    String evidence = new JSONObject().put("outcome", "visible_after_hide")
                            .put("route", route).put("marker_present", true).put("samples", 2)
                            .put("width", bounds.width()).put("height", bounds.height()).toString();
                    reported = true;
                    sink.contradiction(feature, route, evidence);
                } catch (Exception unavailable) { /* An unavailable observation is not drift. */ }
            }, 500);
        }, 500);
    }

    private static boolean visible(View view, CurrentMarker marker) {
        try {
            if (!view.isAttachedToWindow() || !view.isShown() || !view.hasWindowFocus()
                    || view.getWindowVisibility() != View.VISIBLE || !marker.present()) return false;
            View current = view;
            for (int depth = 0; depth < 32; depth++) {
                if (current.getAlpha() <= 0) return false;
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) {
                    Rect bounds = new Rect();
                    return view.getGlobalVisibleRect(bounds) && !bounds.isEmpty();
                }
                current = (View) parent;
            }
        } catch (Exception unavailable) { /* Detached, rebound, or unknown is not a contradiction. */ }
        return false;
    }
}
