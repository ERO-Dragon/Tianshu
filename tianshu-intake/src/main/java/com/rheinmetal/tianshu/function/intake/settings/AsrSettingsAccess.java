package com.rheinmetal.tianshu.function.intake.settings;

import com.rheinmetal.tianshu.function.intake.settings.AsrConfiguration;

public interface AsrSettingsAccess extends AsrConfiguration {
    void setAsrEnabled(boolean enabled);
    void setSelectedMicName(String name);
    String getAsrGithubProxyUrl();
    void setAsrGithubProxyUrl(String url);
    void setCustomAsrName(String name);
    void setAsrHighPassFilterEnabled(boolean enabled);
    void save();
}
