package com.rheinmetal.tianshu.function.llm.gateway;

public enum LlmGatewayUsageKind {
    TASK,
    INTERACTIVE;

    public static LlmGatewayUsageKind fromName(String value) {
        if (value == null || value.isBlank()) {
            return TASK;
        }
        try {
            return LlmGatewayUsageKind.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return TASK;
        }
    }
}
