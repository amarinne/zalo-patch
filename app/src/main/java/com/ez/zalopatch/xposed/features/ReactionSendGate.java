package com.ez.zalopatch.xposed.features;

/**
 * Pure gating and outcome state for the custom emoji reaction feature.
 *
 * <p>Kept free of Android and Xposed types so the two load-bearing rules are JVM-tested:
 *
 * <ul>
 *   <li>The toggle is armed only when the user enabled it and the reaction bar is not being
 *       hidden by the Hide reaction row tweak.</li>
 *   <li>Constructing the picker or the emoji button never counts as a successful send. A
 *       send failure stays visible until a genuine successful send, so a later picker
 *       cannot mask a broken send path.</li>
 * </ul>
 */
public final class ReactionSendGate {
    public static final String STATUS_INSTALLED = "installed_no_hits";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_ACTIVE = "active";

    private boolean sent;
    private String failure = "";

    private ReactionSendGate() {
    }

    public static ReactionSendGate create() {
        return new ReactionSendGate();
    }

    /** The feature is armed only when the custom toggle is on and the hide toggle is off. */
    public static boolean enabled(boolean customReactionOn, boolean hideReactionRowOn) {
        return customReactionOn && !hideReactionRowOn;
    }

    /**
     * A delayed picker selection must only send while the picker it was opened from is
     * still attached and showing and still holds the listener it was opened with. A rebound
     * menu or a detached picker rejects the selection.
     */
    public static boolean canSend(boolean pickerAttachedAndShown, boolean listenerUnchanged) {
        return canSend(pickerAttachedAndShown, listenerUnchanged, false);
    }

    /**
     * The native expansion path dismisses the picker before it runs, so a selection
     * routed from that interception may send through the captured listener even though
     * the picker is detached. The listener identity must still be unchanged; a rebound
     * menu rejects the selection on every path.
     */
    public static boolean canSend(boolean pickerAttachedAndShown, boolean listenerUnchanged,
                                  boolean detachedAllowed) {
        return listenerUnchanged && (pickerAttachedAndShown || detachedAllowed);
    }

    /** The picker or its button was constructed; deliberately changes no outcome. */
    public synchronized void buttonReady() {
    }

    /** Records a send failure; the failure stays visible until a genuine success. */
    public synchronized void sendFailed(String reason) {
        sent = false;
        failure = reason == null || reason.isEmpty() ? "send failed" : reason;
    }

    /** Records a genuine successful dispatch by the host listener. */
    public synchronized void sendSucceeded() {
        sent = true;
        failure = "";
    }

    public synchronized boolean hasFailed() {
        return !failure.isEmpty();
    }

    public synchronized String status() {
        if (!failure.isEmpty()) {
            return STATUS_FAILED;
        }
        return sent ? STATUS_ACTIVE : STATUS_INSTALLED;
    }

    public synchronized String failureReason() {
        return failure;
    }
}
