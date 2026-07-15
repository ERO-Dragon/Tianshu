package com.rheinmetal.tianshu.client.gui.tts;

import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;

public interface TtsSettingsAccess extends TtsConfiguration {
    void setTtsEnabled(boolean enabled);
    boolean isTtsDiagnosticsEnabled();
    void setTtsDiagnosticsEnabled(boolean enabled);
    String getTtsPreviewText();
    void setTtsPreviewText(String text);
    String getTtsGithubProxyUrl();
    void setTtsGithubProxyUrl(String url);
    void setCustomTtsName(String name);
    void save();
}
