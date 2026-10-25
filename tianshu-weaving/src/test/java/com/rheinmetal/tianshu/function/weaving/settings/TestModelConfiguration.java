package com.rheinmetal.tianshu.function.weaving.settings;

import java.nio.file.Path;

/**
 * Test double that answers {@link WeavingConfiguration} from one {@link LlmModelSettings} document.
 *
 * <p>It mirrors the wiring the loader-side configuration performs, so the migration contract can be
 * asserted on the interface the runtime actually reads instead of on the storage class alone.
 */
final class TestModelConfiguration implements WeavingConfiguration {
    private LlmModelSettings settings;

    TestModelConfiguration(LlmModelSettings settings) {
        this.settings = settings;
    }

    void use(LlmModelSettings next) {
        this.settings = next;
    }

    @Override
    public boolean isLlmEnabled() {
        return true;
    }

    @Override
    public String getCustomLlmName() {
        return "";
    }

    @Override
    public Path getLlmBasePath() {
        return Path.of(".");
    }

    @Override
    public java.util.List<WeavingModelEntry> getLlmModels() {
        return settings.models();
    }

    @Override
    public String getLlmActiveModelId() {
        return settings.activeModelId();
    }

    @Override
    public String getLlmApiKey(String modelId) {
        return settings.apiKey(modelId);
    }

    @Override
    public String getLlmRemoteBaseUrl() {
        return settings.activeModel().baseUrl();
    }

    @Override
    public String getLlmRemoteApiKey() {
        return getLlmApiKey(getLlmActiveModelId());
    }

    @Override
    public String getLlmRemoteModel() {
        return settings.activeModel().modelId();
    }

    @Override
    public int getLlmContextWindowTokens() {
        return settings.activeModel().contextWindowTokens();
    }
}
