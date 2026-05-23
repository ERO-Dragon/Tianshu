package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.llm.inference.LlmGenerationOptions;

public final class AXGenerationOptionsFactory {
    private final AXMemoryWindowPolicy windowPolicy;

    public AXGenerationOptionsFactory(AXMemoryWindowPolicy windowPolicy) {
        this.windowPolicy = windowPolicy == null ? AXMemoryWindowPolicy.DEFAULT : windowPolicy;
    }

    public LlmGenerationOptions create(AXContextSnapshot snapshot) {
        boolean memoryRagAvailable = snapshot != null && snapshot.memoryRagAvailable();
        return LlmGenerationOptions.DEFAULT_STREAMING
                .useMemoryRag(memoryRagAvailable)
                .memoryRagTokenBudget(memoryRagAvailable ? windowPolicy.memoryRagTokenBudget() : 0)
                .includeRagHits(memoryRagAvailable);
    }
}
