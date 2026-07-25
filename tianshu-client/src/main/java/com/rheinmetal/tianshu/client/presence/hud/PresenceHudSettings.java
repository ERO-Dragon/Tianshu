package com.rheinmetal.tianshu.client.presence.hud;

public interface PresenceHudSettings {
    PresenceHudSettings ENABLED = new PresenceHudSettings() {
    };

    default boolean hudEnabled() {
        return true;
    }

    default boolean statusTextEnabled() {
        return true;
    }
}
