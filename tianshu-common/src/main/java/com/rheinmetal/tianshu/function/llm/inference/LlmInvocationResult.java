package com.rheinmetal.tianshu.function.llm.inference;

import java.util.List;

public record LlmInvocationResult(LlmInvocationFinishReason finishReason, String text, LlmInvocationError error, List<LlmRagHit> ragHits) {
    public LlmInvocationResult {
        finishReason = finishReason == null ? LlmInvocationFinishReason.FAILED : finishReason;
        text = text == null ? "" : text;
        ragHits = ragHits == null ? List.of() : List.copyOf(ragHits);
    }

    public LlmInvocationResult(LlmInvocationFinishReason finishReason, String text, LlmInvocationError error) {
        this(finishReason, text, error, List.of());
    }

    public static LlmInvocationResult completed(String text) {
        return new LlmInvocationResult(LlmInvocationFinishReason.COMPLETED, text, null, List.of());
    }

    public static LlmInvocationResult completed(String text, List<LlmRagHit> ragHits) {
        return new LlmInvocationResult(LlmInvocationFinishReason.COMPLETED, text, null, ragHits);
    }

    public static LlmInvocationResult failed(LlmInvocationError error, String text) {
        return new LlmInvocationResult(LlmInvocationFinishReason.FAILED, text, error, List.of());
    }

    public static LlmInvocationResult failed(LlmInvocationError error, String text, List<LlmRagHit> ragHits) {
        return new LlmInvocationResult(LlmInvocationFinishReason.FAILED, text, error, ragHits);
    }

    public static LlmInvocationResult cancelled(String text) {
        return new LlmInvocationResult(LlmInvocationFinishReason.CANCELLED, text, null, List.of());
    }

    public static LlmInvocationResult cancelled(String text, List<LlmRagHit> ragHits) {
        return new LlmInvocationResult(LlmInvocationFinishReason.CANCELLED, text, null, ragHits);
    }
}
