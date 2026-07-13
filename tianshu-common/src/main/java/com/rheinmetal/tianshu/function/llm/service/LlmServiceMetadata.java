package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.ITianshuConfig;

record LlmServiceMetadata(String modelName, String embeddingModelName, int configuredContextSize) {
    static LlmServiceMetadata from(ITianshuConfig config) {
        return config == null
                ? new LlmServiceMetadata("", "", 0)
                : new LlmServiceMetadata(
                        config.getCustomLlmName(),
                        config.getLlmEmbeddingModelName(),
                        config.getLlmContextSize()
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
