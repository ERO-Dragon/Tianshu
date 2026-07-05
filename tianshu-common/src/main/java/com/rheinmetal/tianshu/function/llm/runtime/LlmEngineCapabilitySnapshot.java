package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmEngineCapabilitySnapshot(
        boolean ready,
        boolean supportsThinking,
        boolean supportsMtp,
        boolean supportsEmbeddedMtp,
        boolean externalMtpAvailable,
        int mtpLayerCount
) {
    public LlmEngineCapabilitySnapshot {
        mtpLayerCount = Math.max(0, mtpLayerCount);
    }

    public static LlmEngineCapabilitySnapshot unavailable() {
        return new LlmEngineCapabilitySnapshot(false, false, false, false, false, 0);
    }
}
