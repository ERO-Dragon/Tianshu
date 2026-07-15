package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.model.LlmModelPathResolver;

record LlmServiceMetadata(String modelName, String embeddingModelName, int configuredContextSize) {
    static LlmServiceMetadata from(LlmConfiguration config) {
        if (config == null) {
            return new LlmServiceMetadata("", "", 0);
        }
        LlmModelPathResolver modelProfile = new LlmModelPathResolver(config);
        return new LlmServiceMetadata(
                config.getCustomLlmName(),
                config.getLlmEmbeddingModelName(),
                modelProfile.chatContextSize()
        );
    }

    LlmServiceMetadata {
        modelName = clean(modelName);
        embeddingModelName = clean(embeddingModelName);
        configuredContextSize = Math.max(0, configuredContextSize);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
