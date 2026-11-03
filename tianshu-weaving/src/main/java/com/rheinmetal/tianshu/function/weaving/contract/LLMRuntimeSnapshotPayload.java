package com.rheinmetal.tianshu.function.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

/**
 * What the cloud lane can actually report about itself.
 *
 * <p>There is no local engine to describe: the only runtime facts are readiness, the declared
 * context capacity, and the local embedding lane that retrieval depends on. Model names and the
 * embedding namespace are included so a caller can tell whether stored vectors still belong to the
 * current embedding space.</p>
 */
public record LLMRuntimeSnapshotPayload(
        boolean ready,
        boolean embeddingAvailable,
        int embeddingDimension,
        int contextSize,
        int contextTokenBudget,
        String embeddingModelName,
        String embeddingNamespace,
        String failureMessage,
        long sampledAtMillis
) implements ITianshuPayload {
    public LLMRuntimeSnapshotPayload {
        embeddingDimension = Math.max(-1, embeddingDimension);
        contextSize = Math.max(0, contextSize);
        contextTokenBudget = Math.max(0, contextTokenBudget);
        embeddingModelName = clean(embeddingModelName);
        embeddingNamespace = clean(embeddingNamespace);
        failureMessage = clean(failureMessage);
        sampledAtMillis = sampledAtMillis > 0L ? sampledAtMillis : System.currentTimeMillis();
    }

    public static LLMRuntimeSnapshotPayload unavailable() {
        return new LLMRuntimeSnapshotPayload(
                false, false, -1, 0, 0, "", "", "", System.currentTimeMillis()
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
