package com.rheinmetal.tianshu.function.ia.model;

import java.util.List;

public record DialogueParticipantDescriptor(
        String participantId,
        String moduleId,
        String displayName,
        int priority,
        List<String> supportedIntents,
        List<String> supportedEntityTypes,
        List<String> supportedItemIds,
        DialogueClaimProfile claimProfile,
        DialogueVoiceTriggerGroup voiceTriggerGroup,
        String routeCapability,
        DialogueTurnProcessingPolicy turnProcessingPolicy
) {
    public DialogueParticipantDescriptor(
            String participantId,
            String moduleId,
            String displayName,
            int priority,
            List<String> supportedIntents,
            List<String> supportedEntityTypes,
            List<String> supportedItemIds,
            String routeCapability,
            DialogueTurnProcessingPolicy turnProcessingPolicy
    ) {
        this(
                participantId,
                moduleId,
                displayName,
                priority,
                supportedIntents,
                supportedEntityTypes,
                supportedItemIds,
                DialogueClaimProfile.legacy(supportedIntents, supportedEntityTypes, supportedItemIds),
                DialogueVoiceTriggerGroup.of(supportedIntents, List.of()),
                routeCapability,
                turnProcessingPolicy
        );
    }

    public DialogueParticipantDescriptor(
            String participantId,
            String moduleId,
            String displayName,
            int priority,
            List<String> supportedIntents,
            List<String> supportedEntityTypes,
            List<String> supportedItemIds,
            DialogueClaimProfile claimProfile,
            String routeCapability,
            DialogueTurnProcessingPolicy turnProcessingPolicy
    ) {
        this(
                participantId,
                moduleId,
                displayName,
                priority,
                supportedIntents,
                supportedEntityTypes,
                supportedItemIds,
                claimProfile,
                DialogueVoiceTriggerGroup.EMPTY,
                routeCapability,
                turnProcessingPolicy
        );
    }

    public DialogueParticipantDescriptor {
        participantId = requireText(participantId, "participantId");
        moduleId = requireText(moduleId, "moduleId");
        displayName = sanitize(displayName);
        supportedIntents = copyTextList(supportedIntents);
        supportedEntityTypes = copyTextList(supportedEntityTypes);
        supportedItemIds = copyTextList(supportedItemIds);
        claimProfile = claimProfile == null ? DialogueClaimProfile.legacy(supportedIntents, supportedEntityTypes, supportedItemIds) : claimProfile;
        voiceTriggerGroup = voiceTriggerGroup == null ? DialogueVoiceTriggerGroup.EMPTY : voiceTriggerGroup;
        routeCapability = requireText(routeCapability, "routeCapability");
        turnProcessingPolicy = turnProcessingPolicy == null ? DialogueTurnProcessingPolicy.DEFAULT : turnProcessingPolicy;
    }

    private static List<String> copyTextList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList());
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
