package com.rheinmetal.tianshu.protocol.trigger;

import java.util.List;

public record TriggerRegistration(
        String moduleId,
        List<String> wakeWords,
        List<String> extraWords,
        TriggerCategory category,
        TriggerScope scope,
        boolean dialogueEligible,
        TriggerDeliveryTarget deliveryTarget
) {
    public TriggerRegistration(String moduleId, List<String> wakeWords, List<String> extraWords) {
        this(moduleId, wakeWords, extraWords, TriggerCategory.GENERAL, TriggerScope.CLIENT, false, null);
    }

    public TriggerRegistration(String moduleId, List<String> wakeWords, List<String> extraWords, TriggerCategory category, TriggerScope scope, boolean dialogueEligible) {
        this(moduleId, wakeWords, extraWords, category, scope, dialogueEligible, null);
    }

    public TriggerRegistration {
        moduleId = normalizeRequired(moduleId, "moduleId");
        wakeWords = TextListNormalizer.normalize(wakeWords);
        extraWords = TextListNormalizer.normalize(extraWords);
        category = category == null ? TriggerCategory.GENERAL : category;
        scope = scope == null ? TriggerScope.CLIENT : scope;
        deliveryTarget = deliveryTarget == null ? TriggerDeliveryTarget.defaultFor(moduleId) : deliveryTarget;
        if (!deliveryTarget.moduleId().equals(moduleId)) {
            throw new IllegalArgumentException("deliveryTarget moduleId must match registration moduleId");
        }
        if (wakeWords.isEmpty() && extraWords.isEmpty()) {
            throw new IllegalArgumentException("voice trigger registration must contain at least one word");
        }
    }

    public List<String> commandWords() {
        return extraWords;
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value.trim();
    }
}
