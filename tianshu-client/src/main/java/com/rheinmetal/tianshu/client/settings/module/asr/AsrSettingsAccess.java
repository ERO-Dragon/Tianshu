package com.rheinmetal.tianshu.client.settings.module.asr;

import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;

public interface AsrSettingsAccess extends AsrConfiguration {
    void setAsrEnabled(boolean enabled);
    boolean isAsrDiagnosticsEnabled();
    void setAsrDiagnosticsEnabled(boolean enabled);
    void setSelectedMicName(String name);
    String getAsrGithubProxyUrl();
    void setAsrGithubProxyUrl(String url);
    void setTriggerMode(com.rheinmetal.tianshu.constant.TriggerMode mode);
    void setCustomAsrName(String name);
    void setAsrHighPassFilterEnabled(boolean enabled);
    void setAsrVadEnabled(boolean enabled);
    void save();
}
