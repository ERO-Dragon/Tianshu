package com.rheinmetal.tianshu.protocol.trigger;

public record TriggerDeliveryTarget(String moduleId, String capabilityId) {
    public TriggerDeliveryTarget {
        moduleId = requireText(moduleId, "moduleId");
        capabilityId = capabilityId == null || capabilityId.isBlank()
                ? "VOICE_TRIGGER." + moduleId
                : capabilityId.trim();
    }

    public static TriggerDeliveryTarget defaultFor(String moduleId) {
        return new TriggerDeliveryTarget(moduleId, "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
