package com.rheinmetal.tianshu.function.ia.protocol;

/**
 * The capabilities the dialogue-arbitration side provides.
 *
 * <p>Owned by this module: the ids are its contract. A consumer that hard-depends on intake imports this class;
 * an optional participant uses the id as a string in its bridge.</p>
 */
public final class IaCapabilities {

    public static final String DIALOGUE_ARBITRATE = "DIALOGUE.ARBITRATE";
    public static final String DIALOGUE_PARTICIPANT_REGISTER = "DIALOGUE.PARTICIPANT_REGISTER";
    public static final String DIALOGUE_PARTICIPANT_UNREGISTER = "DIALOGUE.PARTICIPANT_UNREGISTER";
    public static final String DIALOGUE_SESSION_CONTROL = "DIALOGUE.SESSION_CONTROL";

    /**
     * Trusted input path for a user stop gesture.
     *
     * <p>Only the speech input module sends this. It asks the arbiter to resolve the current target and
     * broadcast the request; it does not ask it to cancel anyone on the sender's behalf.</p>
     */
    public static final String DIALOGUE_INTERRUPT = "DIALOGUE.INTERRUPT";

    private IaCapabilities() {
    }
}
