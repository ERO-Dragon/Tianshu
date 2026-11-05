package com.rheinmetal.tianshu.function.weaving.contract;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

import java.util.List;

/**
 * What the cloud lane can actually report about itself.
 *
 * <p>There is no local engine to describe: the runtime facts are readiness, the declared context
 * capacity, the local embedding lane, and the selected provider's reasoning ladder. The ladder is
 * reported as an ordered list: index = tier, and tier {@code 0} is the non-thinking slot when
 * {@code reasoningIncludesNoThinking} is true. A model without a ladder reports a count of 0.</p>
 */
public record LLMRuntimeSnapshotPayload(
        boolean ready,
        boolean embeddingAvailable,
        int embeddingDimension,
        int contextSize,
        int contextTokenBudget,
        String embeddingModelName,
        String embeddingNamespace,
        int reasoningTierCount,
        boolean reasoningIncludesNoThinking,
        int reasoningDefaultTier,
        List<String> reasoningLevels,
        String failureMessage,
        long sampledAtMillis
) implements ITianshuPayload {
    public LLMRuntimeSnapshotPayload {
        embeddingDimension = Math.max(-1, embeddingDimension);
        contextSize = Math.max(0, contextSize);
        contextTokenBudget = Math.max(0, contextTokenBudget);
        embeddingModelName = clean(embeddingModelName);
        embeddingNamespace = clean(embeddingNamespace);
        reasoningTierCount = Math.max(0, reasoningTierCount);
        reasoningDefaultTier = Math.max(0, reasoningDefaultTier);
        reasoningLevels = reasoningLevels == null ? List.of() : List.copyOf(reasoningLevels);
        failureMessage = clean(failureMessage);
        sampledAtMillis = sampledAtMillis > 0L ? sampledAtMillis : System.currentTimeMillis();
    }

    public static LLMRuntimeSnapshotPayload unavailable() {
        return new LLMRuntimeSnapshotPayload(
                false, false, -1, 0, 0, "", "",
                0, false, 0, List.of(),
                "", System.currentTimeMillis()
        );
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
