package com.rheinmetal.tianshu.protocol.dialogue.model;

public record DialogueParticipantDescriptor(
        String participantId,
        String moduleId,
        String displayName,
        int priority,
        DialogueClaimProfile claimProfile,
        DialogueVoiceTriggerGroup voiceTriggerGroup,
        String routeCapability,
        DialogueTurnProcessingPolicy turnProcessingPolicy,
        String scoreCapability,
        double fallbackScore
) {
    public DialogueParticipantDescriptor(
            String participantId,
            String moduleId,
            String displayName,
            int priority,
            DialogueClaimProfile claimProfile,
            DialogueVoiceTriggerGroup voiceTriggerGroup,
            String routeCapability,
            DialogueTurnProcessingPolicy turnProcessingPolicy
    ) {
        this(participantId, moduleId, displayName, priority, claimProfile, voiceTriggerGroup, routeCapability,
                turnProcessingPolicy, "", 0.0D);
    }

    public DialogueParticipantDescriptor {
        participantId = requireText(participantId, "participantId");
        moduleId = requireText(moduleId, "moduleId");
        displayName = sanitize(displayName);
        claimProfile = claimProfile == null ? DialogueClaimProfile.DISABLED : claimProfile;
        voiceTriggerGroup = voiceTriggerGroup == null ? DialogueVoiceTriggerGroup.EMPTY : voiceTriggerGroup;
        routeCapability = requireText(routeCapability, "routeCapability");
        turnProcessingPolicy = turnProcessingPolicy == null ? DialogueTurnProcessingPolicy.DEFAULT : turnProcessingPolicy;
        scoreCapability = sanitize(scoreCapability);
        fallbackScore = clamp(fallbackScore);
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

    private static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
