package com.rheinmetal.tianshu.protocol.dialogue.model;

public enum DialogueClaimConditionType {
    WAKE_WORD,
    HELD_ITEM,
    EQUIPPED_ITEM,
    MENTIONED_ENTITY,
    CROSSHAIR_ENTITY,
    INTERACTION_KEY,
    SNEAKING;

    /**
     * Whether matching this condition counts as a context claim or an utterance claim.
     *
     * <p>Only the two conditions that read the sentence itself belong to {@link DialogueClaimSource#UTTERANCE};
     * everything else reads the state of the game world right now.</p>
     */
    public DialogueClaimSource source() {
        return this == WAKE_WORD || this == MENTIONED_ENTITY
                ? DialogueClaimSource.UTTERANCE
                : DialogueClaimSource.CONTEXT;
    }
}