package com.ez.zalopatch.xposed.features;

import java.util.ArrayList;
import java.util.List;

final class StatusPrivacyAckFilter {
    private StatusPrivacyAckFilter() {
    }

    interface TypeReader {
        int read(Object entry) throws Throwable;
    }

    static Result filterSeen(List<?> source, int seenType, TypeReader reader) {
        ArrayList<Object> kept = new ArrayList<>(source.size());
        int dropped = 0;
        for (Object entry : source) {
            try {
                if (reader.read(entry) == seenType) {
                    dropped++;
                } else {
                    kept.add(entry);
                }
            } catch (Throwable unknownShape) {
                kept.add(entry);
            }
        }
        return new Result(kept, dropped);
    }

    static boolean shouldBlockDirectAck(boolean seen) {
        return seen;
    }

    /**
     * Zalo's shared ACK transport uses two read-receipt opcodes: the last-message
     * receipt and the read-marker path. Other seen=true ACKs also carry delivery.
     */
    static boolean isSeenTransportType(int type) {
        return type == 203 || type == 107;
    }

    static final class Result {
        final ArrayList<Object> kept;
        final int dropped;

        Result(ArrayList<Object> kept, int dropped) {
            this.kept = kept;
            this.dropped = dropped;
        }
    }
}
