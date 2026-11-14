package com.rheinmetal.tianshu.function.intake.settings;


public interface IntakeSettingsAccess extends IntakeConfiguration {
    void setAsrEnabled(boolean enabled);
    void setSelectedMicName(String name);
    String getAsrGithubProxyUrl();
    void setAsrGithubProxyUrl(String url);
    void setCustomAsrName(String name);
    void setAsrHighPassFilterEnabled(boolean enabled);
    void save();
}
