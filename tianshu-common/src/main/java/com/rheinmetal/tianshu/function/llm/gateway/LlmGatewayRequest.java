package com.rheinmetal.tianshu.function.llm.gateway;

import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagRoutingContext;

import java.util.List;

public record LlmGatewayRequest(
        String taskId,
        String purpose,
        LlmGatewayUsageKind usageKind,
        String sourceId,
        String traceId,
        List<LlmInvocationMessage> messages,
        List<String> dynamicFacts,
        int taskPriority,
        boolean taskPreemptible,
        boolean stream,
        boolean thinking,
        boolean useRag,
        int maxTokens,
        double temperature,
        LlmRagRoutingContext ragRouting,
        LlmUsageAuthorization authorization,
        long expireAtMillis,
        long createdAtMillis
) {
    public LlmGatewayRequest {
        taskId = normalize(taskId, "llm.task");
        purpose = normalize(purpose, "llm.task");
        usageKind = usageKind == null ? LlmGatewayUsageKind.TASK : usageKind;
        sourceId = normalize(sourceId, "unknown");
        traceId = normalize(traceId, taskId);
        messages = messages == null ? List.of() : List.copyOf(messages.stream().filter(message -> message != null).toList());
        dynamicFacts = dynamicFacts == null ? List.of() : List.copyOf(dynamicFacts.stream().filter(fact -> fact != null && !fact.isBlank()).map(String::trim).toList());
        if (Double.isNaN(temperature) || Double.isInfinite(temperature) || temperature < 0.0D || temperature > 2.0D) {
            temperature = 0.2D;
        }
        if (maxTokens < 0) {
            maxTokens = 0;
        }
        ragRouting = ragRouting == null ? LlmRagRoutingContext.EMPTY : ragRouting;
        authorization = authorization == null ? LlmUsageAuthorization.EMPTY : authorization;
        if (createdAtMillis <= 0L) {
            createdAtMillis = System.currentTimeMillis();
        }
    }

    public boolean isExpired(long now) {
        return expireAtMillis > 0L && now >= expireAtMillis;
    }

    public boolean requiresAuthorization() {
        return usageKind == LlmGatewayUsageKind.INTERACTIVE;
    }

    public boolean hasAuthorizationContext() {
        return authorization.isPresent();
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
