package com.rheinmetal.tianshu.protocol.dialogue.model;

/**
 * How one participant addresses itself to the dialogue domain.
 *
 * @param factoryDefaultOwner when the player never chose a default owner, this participant is the out-of-box one.
 *                            Only meaningful together with a {@link DialogueClaimMode#DEFAULT_OWNER} claim profile:
 *                            a participant that cannot own an unclaimed turn cannot be the default owner either.
 *                            The product keeps one of these (the host's own assistant); a third-party module that
 *                            sets it would compete on priority, which is the platform's normal tie-break.
 */
public record DialogueParticipantDescriptor(
        String participantId,
        String moduleId,
        String displayName,
        int priority,
        DialogueClaimProfile claimProfile,
        DialogueTriggerGroup triggerGroup,
        String routeCapability,
        DialogueTurnProcessingPolicy turnProcessingPolicy,
        String icon,
        DialogueHoldTier holdTier,
        boolean factoryDefaultOwner
) {
    public DialogueParticipantDescriptor(
            String participantId,
            String moduleId,
            String displayName,
            int priority,
            DialogueClaimProfile claimProfile,
            DialogueTriggerGroup triggerGroup,
            String routeCapability,
            DialogueTurnProcessingPolicy turnProcessingPolicy
    ) {
        this(participantId, moduleId, displayName, priority, claimProfile, triggerGroup, routeCapability,
                turnProcessingPolicy, "", DialogueHoldTier.NONE);
    }

    public DialogueParticipantDescriptor(
            String participantId,
            String moduleId,
            String displayName,
            int priority,
            DialogueClaimProfile claimProfile,
            DialogueTriggerGroup triggerGroup,
            String routeCapability,
            DialogueTurnProcessingPolicy turnProcessingPolicy,
            String icon,
            DialogueHoldTier holdTier
    ) {
        this(participantId, moduleId, displayName, priority, claimProfile, triggerGroup, routeCapability,
                turnProcessingPolicy, icon, holdTier, false);
    }

    public DialogueParticipantDescriptor {
        participantId = requireText(participantId, "participantId");
        moduleId = requireText(moduleId, "moduleId");
        displayName = sanitize(displayName);
        claimProfile = claimProfile == null ? DialogueClaimProfile.DISABLED : claimProfile;
        triggerGroup = triggerGroup == null ? DialogueTriggerGroup.EMPTY : triggerGroup;
        routeCapability = requireText(routeCapability, "routeCapability");
        turnProcessingPolicy = turnProcessingPolicy == null ? DialogueTurnProcessingPolicy.DEFAULT : turnProcessingPolicy;
        icon = sanitize(icon);
        holdTier = holdTier == null ? DialogueHoldTier.NONE : holdTier;
    }

    /** Whether the partner marker has something to draw; a participant without an icon is expressed another way. */
    public boolean hasPartnerIcon() {
        return !icon.isBlank();
    }

    /** Whether this participant can own a turn that no claim and no lease selected. */
    public boolean canBeDefaultOwner() {
        return claimProfile.mode() == DialogueClaimMode.DEFAULT_OWNER;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}