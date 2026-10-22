package com.rheinmetal.tianshu.function.echo.settings;

import com.rheinmetal.tianshu.function.echo.settings.TtsConfiguration;

public interface TtsSettingsAccess extends TtsConfiguration {
    void setTtsEnabled(boolean enabled);
    void setTtsStreamingSynthesisEnabled(boolean enabled);
    String getTtsPreviewText();
    void setTtsPreviewText(String text);
    String getTtsGithubProxyUrl();
    void setTtsGithubProxyUrl(String url);
    void setCustomTtsName(String name);
    void save();
}
