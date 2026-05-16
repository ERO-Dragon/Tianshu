package com.rheinmetal.tianshu.function.llm.gateway;

public record LlmUsageAuthorizationCompletion(
        String taskId,
        LlmUsageAuthorizationDecision decision
) {
}
