package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

/**
 * Per-runtime metadata: which cloud model is configured, which embedding model feeds RAG, and the
 * declared context window of the cloud model.
 *
 * <p>The window is read from {@link LlmConfiguration#getLlmContextWindowTokens()} — the cloud
 * endpoint never reports its own window, so the configured number is the only source. It is a
 * fallback only (see {@code LlmRuntimeInspector}): when the inference client can plan a budget, the
 * plan wins. The local GGUF chat catalog is deliberately not consulted here: it describes models this
 * runtime never loads for chat, and reading it would make an unlisted model silently shrink every AX
 * budget to that catalog's default.</p>
 */
record LlmServiceMetadata(String modelName, String embeddingModelName, int declaredContextWindowTokens) {
    static LlmServiceMetadata from(LlmConfiguration config) {
        if (config == null) {
            return new LlmServiceMetadata("", "", 0);
        }
        return new LlmServiceMetadata(
                config.getCustomLlmName(),
                config.getLlmEmbeddingModelName(),
                config.getLlmContextWindowTokens()
        );
    }

    LlmServiceMetadata {
        modelName = clean(modelName);
        embeddingModelName = clean(embeddingModelName);
        declaredContextWindowTokens = Math.max(0, declaredContextWindowTokens);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
