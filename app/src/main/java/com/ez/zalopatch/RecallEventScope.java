package com.ez.zalopatch;

/**
 * Marks the synchronous local undo handler while it runs.
 *
 * <p>The host shares one tombstone coordinator between recall and delete controls. The
 * coordinator hook can only keep recalled text when the resolved undo handler is the
 * event source; a delete control reaches the same coordinator through a different caller
 * and must stay native. Depth tracking keeps nested calls safe and thread isolation keeps
 * unrelated chats native.
 */
public final class RecallEventScope {
    private static final ThreadLocal<Integer> UNDO_DEPTH = new ThreadLocal<>();

    private RecallEventScope() {
    }

    public static void enterUndo() {
        UNDO_DEPTH.set(depth() + 1);
    }

    public static void leaveUndo() {
        int next = depth() - 1;
        if (next <= 0) {
            UNDO_DEPTH.remove();
        } else {
            UNDO_DEPTH.set(next);
        }
    }

    public static boolean inUndo() {
        return depth() > 0;
    }

    private static int depth() {
        Integer value = UNDO_DEPTH.get();
        return value == null ? 0 : value;
    }
}
