package com.rheinmetal.tianshu.function.ia.protocol;

/**
 * The topics the dialogue-arbitration side produces.
 *
 * <p>Owned by this module for the same reason its payload types are: the kernel registers mechanism, not a
 * feature's vocabulary.</p>
 */
public final class IaTopics {

    public static final String DIALOGUE_SESSION_EVENTS = "DIALOGUE.SESSION_EVENTS";

    private IaTopics() {
    }
}
