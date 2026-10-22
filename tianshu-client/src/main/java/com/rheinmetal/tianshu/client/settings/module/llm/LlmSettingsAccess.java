package com.rheinmetal.tianshu.client.settings.module.llm;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmModelEntry;

import java.util.List;

public interface LlmSettingsAccess extends LlmConfiguration {
    void setLlmEnabled(boolean enabled);
    void setCustomLlmName(String name);
    void setLlmRemoteBaseUrl(String baseUrl);
    /** The credential itself is never read back into the settings page; only written here. */
    void setLlmRemoteApiKey(String apiKey);
    void setLlmRemoteModel(String model);

    /*
     * Multi-model writes. The single-model setters above stay valid and keep addressing the active
     * entry, so a caller that predates the model list does not have to change.
     */

    /** Replaces the whole model list; an empty list hands the profile back to the legacy keys. */
    void setLlmModels(List<LlmModelEntry> models);
    /** Selects the active entry by id; an unknown id resolves to the first entry. */
    void setLlmActiveModelId(String modelId);
    /**
     * Stores the credential of one entry under its own key. It is deliberately not a field of
     * {@link LlmModelEntry}, so it can never be serialised together with the model list.
     */
    void setLlmApiKey(String modelId, String apiKey);

    void save();
}
