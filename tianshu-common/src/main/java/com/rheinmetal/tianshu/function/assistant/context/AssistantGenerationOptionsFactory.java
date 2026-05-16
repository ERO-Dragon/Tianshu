package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.function.llm.inference.LlmGenerationOptions;

public final class AssistantGenerationOptionsFactory {
    private final AssistantMemoryWindowPolicy windowPolicy;

    public AssistantGenerationOptionsFactory(AssistantMemoryWindowPolicy windowPolicy) {
        this.windowPolicy = windowPolicy == null ? AssistantMemoryWindowPolicy.DEFAULT : windowPolicy;
    }

    public LlmGenerationOptions create(AssistantContextSnapshot snapshot) {
        boolean memoryRagAvailable = snapshot != null && snapshot.memoryRagAvailable();
        return LlmGenerationOptions.DEFAULT_STREAMING
                .useMemoryRag(memoryRagAvailable)
                .memoryRagTokenBudget(memoryRagAvailable ? windowPolicy.memoryRagTokenBudget() : 0)
                .includeRagHits(memoryRagAvailable);
    }
}
