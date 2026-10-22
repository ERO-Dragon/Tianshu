package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

/**
 * Per-runtime metadata: the model name the runtime is attributed to, the cloud model whose sampling
 * defaults apply, the embedding model that feeds RAG, and the declared context window.
 *
 * <p>{@code modelName} is the configured runtime name and is what the usage ledger and the runtime
 * snapshot are keyed by; keeping it stable keeps existing attribution intact. {@code samplingModelId} is
 * a separate fact: the bundled catalog describes cloud models by their provider id, so the request
 * preparer needs that id to find a model's sampling defaults. Folding the two together would re-key every
 * usage record the moment the sampling source changed.
 *
 * <p>The window is read from {@link LlmConfiguration#getLlmContextWindowTokens()} — the cloud endpoint
 * never reports its own window, so the configured number is the only source. It is a fallback only (see
 * {@code LlmRuntimeInspector}): when the inference client can plan a budget, the plan wins.</p>
 */
record LlmServiceMetadata(String modelName, String samplingModelId, String embeddingModelName,
                          int declaredContextWindowTokens) {
    static LlmServiceMetadata from(LlmConfiguration config) {
        if (config == null) {
            return new LlmServiceMetadata("", "", "", 0);
        }
        return new LlmServiceMetadata(
                config.getCustomLlmName(),
                config.getLlmRemoteModel(),
                config.getLlmEmbeddingModelName(),
                config.getLlmContextWindowTokens()
        );
    }

    LlmServiceMetadata {
        modelName = clean(modelName);
        samplingModelId = clean(samplingModelId);
        embeddingModelName = clean(embeddingModelName);
        declaredContextWindowTokens = Math.max(0, declaredContextWindowTokens);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
