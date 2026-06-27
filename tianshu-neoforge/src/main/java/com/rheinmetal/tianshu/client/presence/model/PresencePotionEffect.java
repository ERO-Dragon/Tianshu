package com.rheinmetal.tianshu.client.presence.model;

public record PresencePotionEffect(
        String effectId,
        String displayName,
        int durationTicks,
        int amplifier,
        boolean beneficial
) {
    public PresencePotionEffect {
        effectId = clean(effectId);
        displayName = clean(displayName);
        durationTicks = Math.max(0, durationTicks);
        amplifier = Math.max(0, amplifier);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
