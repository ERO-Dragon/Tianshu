package com.rheinmetal.tianshu.client.settings.module.presence;

public interface PresenceSettingsAccess {
    boolean isPresenceHudEnabled();
    void setPresenceHudEnabled(boolean enabled);
    boolean isPresenceStatusTextEnabled();
    void setPresenceStatusTextEnabled(boolean enabled);
    boolean isPresenceAsrStatusVisible();
    void setPresenceAsrStatusVisible(boolean visible);
    boolean isPresenceLlmStatusVisible();
    void setPresenceLlmStatusVisible(boolean visible);
    boolean isPresenceTtsStatusVisible();
    void setPresenceTtsStatusVisible(boolean visible);
    boolean isPresenceAxStatusVisible();
    void setPresenceAxStatusVisible(boolean visible);
    boolean isPresenceDebugPipelineEnabled();
    void setPresenceDebugPipelineEnabled(boolean enabled);
    void save();
}
