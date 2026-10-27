package com.rheinmetal.tianshu.protocol.dialogue.model;

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
        DialogueHoldTier holdTier
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

    /** Whether the partner marker has something to draw; the default owner never draws one. */
    public boolean hasPartnerIcon() {
        return !icon.isBlank();
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
