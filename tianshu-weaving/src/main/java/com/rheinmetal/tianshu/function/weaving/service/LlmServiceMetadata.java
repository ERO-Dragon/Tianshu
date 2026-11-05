package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;

/**
 * The two facts the service needs from configuration: which embedding model feeds retrieval, and how
 * much context the selected cloud model declares.
 */
record LlmServiceMetadata(String embeddingModelName, int declaredContextWindowTokens) {
    static LlmServiceMetadata from(WeavingConfiguration config) {
        if (config == null) {
            return new LlmServiceMetadata("", 0);
        }
        return new LlmServiceMetadata(
                clean(config.getLlmEmbeddingModelName()),
                Math.max(0, config.getLlmContextWindowTokens())
        );
    }

    LlmServiceMetadata {
        embeddingModelName = clean(embeddingModelName);
        declaredContextWindowTokens = Math.max(0, declaredContextWindowTokens);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
