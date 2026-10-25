package com.rheinmetal.tianshu.function.weaving.settings;


import java.util.List;

public interface WeavingSettingsAccess extends WeavingConfiguration {
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
    void setLlmModels(List<WeavingModelEntry> models);
    /** Selects the active entry by id; an unknown id resolves to the first entry. */
    void setLlmActiveModelId(String modelId);
    /**
     * Stores the credential of one entry under its own key. It is deliberately not a field of
     * {@link WeavingModelEntry}, so it can never be serialised together with the model list.
     */
    void setLlmApiKey(String modelId, String apiKey);

    /*
     * Embedding position. The same model runs either on the card or on the CPU, so this is one value
     * rather than a model property; the read side stays on the configuration port.
     */

    /**
     * Puts every layer on the card ({@link WeavingConfiguration#EMBEDDING_GPU_LAYERS_ALL}) or keeps the
     * whole lane on the CPU ({@code 0}).
     */
    void setLlmEmbeddingGpuLayers(int layers);

    void save();
}
