package com.rheinmetal.tianshu.protocol.voice;

public record VoiceTriggerDeliveryTarget(String moduleId, String capabilityId) {
    public VoiceTriggerDeliveryTarget {
        moduleId = requireText(moduleId, "moduleId");
        capabilityId = capabilityId == null || capabilityId.isBlank()
                ? "VOICE_TRIGGER." + moduleId
                : capabilityId.trim();
    }

    public static VoiceTriggerDeliveryTarget defaultFor(String moduleId) {
        return new VoiceTriggerDeliveryTarget(moduleId, "");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
