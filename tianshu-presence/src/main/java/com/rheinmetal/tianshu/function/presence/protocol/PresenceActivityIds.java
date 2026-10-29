package com.rheinmetal.tianshu.function.presence.protocol;

/**
 * Activity ids that more than one module has to agree on.
 *
 * <p>An activity is identified by its publisher, its id and its type. Most ids live inside one module; a
 * turn-scoped activity is opened by one module and closed by another, so both sides have to build the same id.
 * Keeping that spelling here means the opener and the closer cannot drift apart.</p>
 */
public final class PresenceActivityIds {
    /** The listener mark of one speech turn; opened by the speech module, closed by the arbiter. */
    public static String listening(long sessionId) {
        return "asr.speech." + sessionId;
    }

    private PresenceActivityIds() {
    }
}