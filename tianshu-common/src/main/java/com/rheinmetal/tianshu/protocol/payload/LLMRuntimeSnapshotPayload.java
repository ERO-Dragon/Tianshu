package com.rheinmetal.tianshu.protocol.payload;

import com.rheinmetal.tianshu.protocol.ITianshuPayload;

public record LLMRuntimeSnapshotPayload(
        boolean ready,
        boolean modelLoaded,
        boolean embeddingAvailable,
        int embeddingDimension,
        boolean supportsThinking,
        boolean supportsMtp,
        boolean supportsEmbeddedMtp,
        boolean externalMtpAvailable,
        boolean mtpCalibrated,
        int mtpLayerCount,
        int recommendedDraftMax,
        int contextSize,
        int contextTokenBudget,
        boolean chatQueueCapacity,
        boolean taskQueueCapacity,
        boolean queueCapacity,
        int chatQueueSize,
        int taskQueueSize,
        int queueSize,
        String modelName,
        String modelProfile,
        String embeddingModelName,
        String embeddingNamespace,
        String failureMessage,
        long sampledAtMillis
) implements ITianshuPayload {
    public LLMRuntimeSnapshotPayload {
        embeddingDimension = embeddingDimension < 0 ? -1 : embeddingDimension;
        mtpLayerCount = Math.max(0, mtpLayerCount);
        recommendedDraftMax = Math.max(0, recommendedDraftMax);
        contextSize = Math.max(0, contextSize);
        contextTokenBudget = Math.max(0, contextTokenBudget);
        chatQueueSize = Math.max(0, chatQueueSize);
        taskQueueSize = Math.max(0, taskQueueSize);
        queueSize = Math.max(0, queueSize);
        modelName = modelName == null ? "" : modelName.trim();
        modelProfile = modelProfile == null ? "" : modelProfile.trim();
        embeddingModelName = embeddingModelName == null ? "" : embeddingModelName.trim();
        embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace.trim();
        failureMessage = failureMessage == null ? "" : failureMessage.trim();
        sampledAtMillis = sampledAtMillis > 0L ? sampledAtMillis : System.currentTimeMillis();
    }

    public static LLMRuntimeSnapshotPayload unavailable() {
        return new LLMRuntimeSnapshotPayload(false, false, false, -1, false, false, false, false, false, 0, 0, 0, 0,
                false, false, false, 0, 0, 0, "", "", "", "", "", System.currentTimeMillis());
    }
}
