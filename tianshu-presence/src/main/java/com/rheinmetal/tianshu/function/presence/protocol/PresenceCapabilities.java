package com.rheinmetal.tianshu.function.presence.protocol;

/**
 * The capability presence exposes.
 *
 * <p>The name is presence's own contract: consumers (the arbiter, the dialogue brain) ask for it by this id, and the
 * kernel never needs to know it exists.</p>
 */
public final class PresenceCapabilities {

    /** Answers "what is the player doing right now" from a capture taken when the question is answered. */
    public static final String QUERY_CONTEXT = "PRESENCE.QUERY_CONTEXT";

    private PresenceCapabilities() {
    }
}
