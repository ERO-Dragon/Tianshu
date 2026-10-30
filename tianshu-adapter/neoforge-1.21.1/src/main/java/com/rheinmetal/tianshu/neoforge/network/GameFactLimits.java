package com.rheinmetal.tianshu.neoforge.network;

/**
 * The bounds a fact must satisfy before the client believes what the peer declared.
 *
 * <p>The decoder used to take the value count straight from the wire and hand it to a map's initial
 * capacity, so a six-byte packet could ask a client to allocate a multi-gigabyte table and die on
 * {@code OutOfMemoryError} — an {@code Error}, which no diagnostic layer can catch. A client connects to
 * servers it does not control, so the count is validated before anything is allocated.</p>
 *
 * <p>The text bound deliberately equals what {@code writeUtf} can produce (32767), so a packet this
 * build itself writes can never be rejected by it: the limit exists to stop absurd declarations, not to
 * shrink the format. The count bound is far above what the server component sends (at most four keys per
 * fact) for the same reason.</p>
 */
final class GameFactLimits {

    /** Comfortably above the four keys {@code GameFactCollector} ever sends. */
    static final int MAX_VALUES = 64;

    /** The largest string {@code writeUtf} can put on the wire, so valid data is never rejected. */
    static final int MAX_TEXT_LENGTH = 32_767;

    private GameFactLimits() {
    }

    static int requireValueCount(int declaredCount) {
        if (declaredCount < 0 || declaredCount > MAX_VALUES) {
            throw new IllegalArgumentException("game fact declares an invalid value count: " + declaredCount);
        }
        return declaredCount;
    }

    static String requireText(String value, String field) {
        if (value == null) {
            return "";
        }
        if (value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("game fact " + field + " is longer than the wire allows: "
                    + value.length());
        }
        return value;
    }
}
