package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmPerformanceSnapshot(
        boolean available,
        boolean llmUsesGpu,
        boolean llmSharesRenderGpu,
        int fps,
        boolean gpuUtilizationAvailable,
        double gpuUtilization,
        long vramUsedBytes,
        long vramTotalBytes,
        long sampledAtMillis
) {
    public LlmPerformanceSnapshot {
        fps = Math.max(0, fps);
        gpuUtilization = Math.max(0.0D, Math.min(1.0D, gpuUtilization));
        vramUsedBytes = Math.max(0L, vramUsedBytes);
        vramTotalBytes = Math.max(0L, vramTotalBytes);
        sampledAtMillis = sampledAtMillis > 0L ? sampledAtMillis : System.currentTimeMillis();
    }

    public static LlmPerformanceSnapshot unavailable() {
        return new LlmPerformanceSnapshot(false, false, false, 0, false, 0.0D, 0L, 0L, System.currentTimeMillis());
    }
}
