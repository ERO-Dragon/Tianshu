package com.rheinmetal.tianshu.function.llm.inference;

public record LlmInvocationError(String code, String message, Throwable cause) {
    public LlmInvocationError {
        code = code == null || code.isBlank() ? "LLM_INVOCATION_FAILED" : code.trim();
        message = message == null ? "" : message;
    }
}
