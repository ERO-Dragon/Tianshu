package com.rheinmetal.tianshu.protocol.dialogue.model;

/**
 * Where a hard claim came from; it ranks claims before participant priority.
 *
 * <p>{@link #CONTEXT} is "what the player is doing right now": crosshair target, held item, interaction
 * state. Being pointed at is a reservation. {@link #UTTERANCE} is "what the player just said": wake words and
 * named entities. Naming someone is not a reservation, so a context claim always outranks a wake-word claim —
 * pointing at 酒狐 while saying another module's wake word still goes to 酒狐.</p>
 *
 * <p>Declaration order is the ranking order: later wins.</p>
 */
public enum DialogueClaimSource {
    UTTERANCE,
    CONTEXT
}
