package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;

public final class ClientConfigPresenceHudSettings implements PresenceHudSettings {
    private final ClientConfig config;

    public ClientConfigPresenceHudSettings(ClientConfig config) {
        this.config = config;
    }

    @Override
    public boolean hudEnabled() {
        return config == null || config.isPresenceHudEnabled();
    }

    @Override
    public boolean statusTextEnabled() {
        return config == null || config.isPresenceStatusTextEnabled();
    }

    @Override
    public boolean iconEnabled() {
        return config == null || config.isPresenceIconEnabled();
    }

    @Override
    public double iconSizePixels() {
        return config == null ? DEFAULT_ICON_SIZE_PIXELS : config.getPresenceIconSize();
    }

    @Override
    public PresenceHudVisualPreset visualPreset() {
        return config == null ? PresenceHudVisualPreset.PRESET_ONE : config.getPresenceVisualPreset();
    }

    @Override
    public double iconPositionX() {
        return config == null ? 0.5D : config.getPresenceIconPositionX();
    }

    @Override
    public double iconPositionY() {
        return config == null ? -1.0D : config.getPresenceIconPositionY();
    }
}
