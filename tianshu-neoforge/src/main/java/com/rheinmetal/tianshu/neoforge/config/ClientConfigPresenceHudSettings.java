package com.rheinmetal.tianshu.neoforge.config;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;

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
}
