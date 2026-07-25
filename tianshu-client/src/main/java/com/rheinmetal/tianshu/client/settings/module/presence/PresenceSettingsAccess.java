package com.rheinmetal.tianshu.client.settings.module.presence;

public interface PresenceSettingsAccess {
    boolean isPresenceHudEnabled();
    void setPresenceHudEnabled(boolean enabled);
    boolean isPresenceStatusTextEnabled();
    void setPresenceStatusTextEnabled(boolean enabled);
    void save();
}
