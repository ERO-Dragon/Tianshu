package com.rheinmetal.tianshu.client.host.game;

/** The small numeric extras a HUD wants on top of health, hunger and level. */
public record GamePlayerVitals(
        float experienceProgress,
        float saturation,
        float absorption
) {
    public static final GamePlayerVitals EMPTY = new GamePlayerVitals(0.0F, 0.0F, 0.0F);

    public GamePlayerVitals {
        experienceProgress = clamp(experienceProgress, 0.0F, 1.0F);
        saturation = Math.max(0.0F, saturation);
        absorption = Math.max(0.0F, absorption);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
