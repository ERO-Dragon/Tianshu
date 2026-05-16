package com.rheinmetal.tianshu.function.llm.inference;

public record LlmGenerationOptions(double temperature, boolean stream, boolean thinking, int maxTokens, LlmInvocationLane lane, boolean useRag, boolean useMemoryRag, int memoryRagTokenBudget, boolean includeRagHits, int taskPriority, boolean taskPreemptible) {
    public static final LlmGenerationOptions DEFAULT_STREAMING = new LlmGenerationOptions(0.6D, true, false, 0, LlmInvocationLane.CHAT, true, true, 1000, true, 0, false);
    public static final LlmGenerationOptions DEFAULT_COLLECTING = new LlmGenerationOptions(0.6D, false, false, 0, LlmInvocationLane.CHAT, true, true, 1000, true, 0, false);
    public static final LlmGenerationOptions DEFAULT_TASK = new LlmGenerationOptions(0.2D, false, false, 512, LlmInvocationLane.TASK, false, false, 1000, true, 0, false);

    public LlmGenerationOptions {
        if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature < 0.0D || temperature > 2.0D) {
            temperature = 0.6D;
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
        if (memoryRagTokenBudget < 0) {
            memoryRagTokenBudget = 0;
        }
        lane = lane == null ? LlmInvocationLane.CHAT : lane;
    }

    public LlmGenerationOptions(double temperature, boolean stream, boolean thinking, int maxTokens) {
        this(temperature, stream, thinking, maxTokens, LlmInvocationLane.CHAT, true, true, 1000, true, 0, false);
    }

    public LlmGenerationOptions(double temperature, boolean stream, boolean thinking) {
        this(temperature, stream, thinking, 0, LlmInvocationLane.CHAT, true, true, 1000, true, 0, false);
    }

    public LlmGenerationOptions streaming(boolean stream) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions thinking(boolean thinking) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions temperature(double temperature) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions maxTokens(int maxTokens) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions lane(LlmInvocationLane lane) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions useRag(boolean useRag) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions useMemoryRag(boolean useMemoryRag) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions memoryRagTokenBudget(int memoryRagTokenBudget) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions includeRagHits(boolean includeRagHits) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions taskPriority(int taskPriority) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }

    public LlmGenerationOptions taskPreemptible(boolean taskPreemptible) {
        return new LlmGenerationOptions(temperature, stream, thinking, maxTokens, lane, useRag, useMemoryRag, memoryRagTokenBudget, includeRagHits, taskPriority, taskPreemptible);
    }
}
