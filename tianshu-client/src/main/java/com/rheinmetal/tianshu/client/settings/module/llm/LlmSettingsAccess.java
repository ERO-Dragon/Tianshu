package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

public interface LlmSettingsAccess extends LlmConfiguration {
    void setLlmEnabled(boolean enabled);
    void setCustomLlmName(String name);
    void setLlmRemoteBaseUrl(String baseUrl);
    /** The credential itself is never read back into the settings page; only written here. */
    void setLlmRemoteApiKey(String apiKey);
    void setLlmRemoteModel(String model);
    void save();
}
