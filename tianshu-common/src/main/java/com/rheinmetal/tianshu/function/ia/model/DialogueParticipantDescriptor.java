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
        String routeCapability,
        DialogueInterruptPolicy interruptPolicy,
        DialogueLeasePolicy leasePolicy
) {
    public DialogueParticipantDescriptor {
        participantId = requireText(participantId, "participantId");
        moduleId = requireText(moduleId, "moduleId");
        displayName = sanitize(displayName);
        supportedIntents = copyTextList(supportedIntents);
        supportedEntityTypes = copyTextList(supportedEntityTypes);
        supportedItemIds = copyTextList(supportedItemIds);
        routeCapability = requireText(routeCapability, "routeCapability");
        interruptPolicy = interruptPolicy == null ? DialogueInterruptPolicy.ALLOW_AFTER_LEASE : interruptPolicy;
        leasePolicy = leasePolicy == null ? DialogueLeasePolicy.DEFAULT : leasePolicy;
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
