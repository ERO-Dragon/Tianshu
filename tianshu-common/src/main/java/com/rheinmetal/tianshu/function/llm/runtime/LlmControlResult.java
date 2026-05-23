package com.rheinmetal.tianshu.function.llm.runtime;

public record LlmControlResult(
        boolean accepted,
        LlmRuntimeState state,
        String message,
        long occurredAtMillis
) {
    public LlmControlResult {
        state = state == null ? LlmRuntimeState.FAILED : state;
        message = message == null ? "" : message.trim();
        occurredAtMillis = occurredAtMillis > 0L ? occurredAtMillis : System.currentTimeMillis();
    }

    public static LlmControlResult accepted(LlmRuntimeState state, String message) {
        return new LlmControlResult(true, state, message, System.currentTimeMillis());
    }

    public static LlmControlResult rejected(String message) {
        return new LlmControlResult(false, LlmRuntimeState.FAILED, message, System.currentTimeMillis());
    }
}
