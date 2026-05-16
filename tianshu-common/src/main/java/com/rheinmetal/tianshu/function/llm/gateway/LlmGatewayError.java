package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmGatewayError(String code, String message) {
    public LlmGatewayError {
        code = code == null || code.isBlank() ? "LLM_GATEWAY_ERROR" : code.trim();
        message = message == null ? "" : message;
    }
}
