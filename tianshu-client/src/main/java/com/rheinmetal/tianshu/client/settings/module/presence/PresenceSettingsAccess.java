package com.rheinmetal.tianshu.client.settings.module.presence;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;

public interface PresenceSettingsAccess {
    boolean isPresenceHudEnabled();
    void setPresenceHudEnabled(boolean enabled);
    boolean isPresenceStatusTextEnabled();
    void setPresenceStatusTextEnabled(boolean enabled);
    boolean isPresenceIconEnabled();
    void setPresenceIconEnabled(boolean enabled);
    double getPresenceIconSize();
    void setPresenceIconSize(double size);
    PresenceHudVisualPreset getPresenceVisualPreset();
    void setPresenceVisualPreset(PresenceHudVisualPreset preset);
    double getPresenceIconPositionX();
    void setPresenceIconPositionX(double position);
    double getPresenceIconPositionY();
    void setPresenceIconPositionY(double position);
    void save();
}
