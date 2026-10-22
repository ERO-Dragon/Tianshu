package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;

/** Resolves normalized HUD coordinates into a screen-safe icon rectangle. */
public record PresenceHudLayout(
        float centerX,
        float centerY,
        float sizePixels,
        boolean defaultAnchor,
        double normalizedX,
        double normalizedY
) {
    private static final double DEFAULT_X = 0.5D;
    private static final double DEFAULT_ANCHOR_SENTINEL = 0.0D;
    private static final float EXPERIENCE_LEVEL_TOP_OFFSET = 35.0F;
    private static final float EXPERIENCE_LEVEL_GAP = 5.0F;

    public static PresenceHudLayout resolve(PresenceHudSettings settings, int screenWidth, int screenHeight) {
        PresenceHudSettings effective = settings == null ? PresenceHudSettings.ENABLED : settings;
        int width = Math.max(1, screenWidth);
        int height = Math.max(1, screenHeight);
        float size = (float) clamp(effective.iconSizePixels(), PresenceHudSettings.MIN_ICON_SIZE_PIXELS,
                PresenceHudSettings.MAX_ICON_SIZE_PIXELS, PresenceHudSettings.DEFAULT_ICON_SIZE_PIXELS);
        double x = clamp01(effective.iconPositionX());
        double configuredY = effective.iconPositionY();
        boolean defaultAnchor = configuredY < DEFAULT_ANCHOR_SENTINEL;
        double y = defaultAnchor ? DEFAULT_ANCHOR_SENTINEL : clamp01(configuredY);
        float centerX = clamp((float) (x * width), size / 2.0F, width - size / 2.0F);
        float centerY = defaultAnchor
                ? height - EXPERIENCE_LEVEL_TOP_OFFSET - EXPERIENCE_LEVEL_GAP - size / 2.0F
                : clamp((float) (y * height), size / 2.0F, height - size / 2.0F);
        return new PresenceHudLayout(centerX, centerY, size, defaultAnchor, x, y);
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_X;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static float clamp(float value, float min, float max) {
        if (max < min) {
            return (min + max) / 2.0F;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
