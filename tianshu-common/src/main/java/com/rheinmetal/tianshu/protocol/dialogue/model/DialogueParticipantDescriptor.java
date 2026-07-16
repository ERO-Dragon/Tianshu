package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueParticipantDescriptor(
        String participantId,
        String moduleId,
        String displayName,
        int priority,
        DialogueClaimProfile claimProfile,
        DialogueVoiceTriggerGroup voiceTriggerGroup,
        String routeCapability,
        DialogueTurnProcessingPolicy turnProcessingPolicy
) {
    public DialogueParticipantDescriptor {
        participantId = requireText(participantId, "participantId");
        moduleId = requireText(moduleId, "moduleId");
        displayName = sanitize(displayName);
        claimProfile = claimProfile == null ? DialogueClaimProfile.DISABLED : claimProfile;
        voiceTriggerGroup = voiceTriggerGroup == null ? DialogueVoiceTriggerGroup.EMPTY : voiceTriggerGroup;
        routeCapability = requireText(routeCapability, "routeCapability");
        turnProcessingPolicy = turnProcessingPolicy == null ? DialogueTurnProcessingPolicy.DEFAULT : turnProcessingPolicy;
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
