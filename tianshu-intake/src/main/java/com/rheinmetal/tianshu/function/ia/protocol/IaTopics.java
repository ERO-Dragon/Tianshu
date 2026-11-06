package com.rheinmetal.tianshu.function.ia.protocol;

/**
 * The topics the dialogue-arbitration side produces.
 *
 * <p>Owned by this module for the same reason its payload types are: the kernel registers mechanism, not a
 * feature's vocabulary.</p>
 */
public final class IaTopics {

    public static final String DIALOGUE_SESSION_EVENTS = "DIALOGUE.SESSION_EVENTS";

    /**
     * One user interrupt request, already resolved to the current arbitration target.
     *
     * <p>Kept beside the session events rather than folded into them: the request changes no session state,
     * and it may name a target that has no session at all (the default owner, a pre-winner).</p>
     */
    public static final String DIALOGUE_INTERRUPT_REQUESTED = "DIALOGUE.INTERRUPT_REQUESTED";

    private IaTopics() {
    }
}
