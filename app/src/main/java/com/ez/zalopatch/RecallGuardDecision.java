package com.ez.zalopatch;

/**
 * Conservative qualification for the recall coordinator gate.
 *
 * <p>The gate keeps a received recall from rewriting a message that is already on this
 * device. It is deliberately narrower than the host: ordinary direct chats only, plain
 * text only, and never the account's own outgoing message. Anything uncertain stays
 * native, so an unsupported message takes Zalo's normal recall path.
 *
 * <p>Dependency-free by design: this is the JVM-tested seam, not the hook.
 */
public final class RecallGuardDecision {
    /** Zalo's ordinary text message type. Media, E2EE, and tombstones stay native. */
    public static final int TEXT_TYPE = 0;

    private RecallGuardDecision() {
    }

    /** True only when every identity and scope condition is positively satisfied. */
    public static boolean qualifies(String sender, String account, String conversationOwner,
                                    int type, String text) {
        if (isEmpty(sender) || isEmpty(account) || isEmpty(conversationOwner)) {
            return false;
        }
        if (sender.equals(account)) {
            return false;
        }
        if (!directConversation(conversationOwner)) {
            return false;
        }
        if (type != TEXT_TYPE) {
            return false;
        }
        return !isEmpty(text);
    }

    /** A direct conversation uses the peer's numeric uid, not the stable group prefix. */
    public static boolean directConversation(String owner) {
        return !isEmpty(owner) && !owner.startsWith("group_");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
