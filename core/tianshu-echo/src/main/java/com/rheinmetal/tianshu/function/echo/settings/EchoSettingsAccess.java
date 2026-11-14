package com.rheinmetal.tianshu.function.echo.settings;


public interface EchoSettingsAccess extends EchoConfiguration {
    void setTtsEnabled(boolean enabled);
    void setTtsStreamingSynthesisEnabled(boolean enabled);
    String getTtsPreviewText();
    void setTtsPreviewText(String text);
    String getTtsGithubProxyUrl();
    void setTtsGithubProxyUrl(String url);
    void setCustomTtsName(String name);
    void setTtsBackendMode(String mode);
    void setCloudProviderId(String providerId);
    void setCloudEndpoint(String endpoint);
    void setCloudVoice(String voice);
    void save();
}
