package com.rheinmetal.tianshu.client.host.game;

/** One active potion effect, named the way the player would say it. */
public record GameEffect(
        String effectId,
        String displayName,
        int durationTicks,
        int amplifier,
        boolean beneficial
) {
    public GameEffect {
        effectId = clean(effectId);
        displayName = clean(displayName);
        durationTicks = Math.max(0, durationTicks);
        amplifier = Math.max(0, amplifier);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}