package com.rheinmetal.tianshu.client.presence.hud;

public interface PresenceHudSettings {
    double MIN_ICON_SIZE_PIXELS = 16.0D;
    double DEFAULT_ICON_SIZE_PIXELS = 28.0D;
    double MAX_ICON_SIZE_PIXELS = 64.0D;

    PresenceHudSettings ENABLED = new PresenceHudSettings() {
    };

    default boolean hudEnabled() {
        return true;
    }

    default boolean statusTextEnabled() {
        return true;
    }

    default boolean iconEnabled() {
        return true;
    }

    default double iconSizePixels() {
        return DEFAULT_ICON_SIZE_PIXELS;
    }

    default PresenceHudVisualPreset visualPreset() {
        return PresenceHudVisualPreset.PRESET_ONE;
    }

    /**
     * Horizontal icon center as a normalized screen coordinate.
     */
    default double iconPositionX() {
        return 0.5D;
    }

    /**
     * Vertical icon center as a normalized screen coordinate. A negative value keeps the default
     * experience-bar anchor, allowing the anchor to follow GUI scaling and icon size changes.
     */
    default double iconPositionY() {
        return -1.0D;
    }
}
