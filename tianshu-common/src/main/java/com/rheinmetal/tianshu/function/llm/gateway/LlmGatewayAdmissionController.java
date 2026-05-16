package com.rheinmetal.tianshu.function.llm.gateway;

public final class LlmGatewayAdmissionController {
    private final LlmGatewayPolicy policy;

    public LlmGatewayAdmissionController(LlmGatewayPolicy policy) {
        this.policy = policy == null ? LlmGatewayPolicy.DEFAULT : policy;
    }

    public LlmGatewayAdmissionResult admit(LlmGatewayRequest request, int pendingCountForSource, long now) {
        if (request == null) {
            return LlmGatewayAdmissionResult.rejected("INVALID_REQUEST", "LLM task request is missing");
        }
        if (request.isExpired(now)) {
            return LlmGatewayAdmissionResult.rejected("TASK_EXPIRED", "LLM task request is expired");
        }
        if (request.messages().isEmpty()) {
            return LlmGatewayAdmissionResult.rejected("EMPTY_MESSAGES", "LLM task request has no messages");
        }
        if (request.messages().size() > policy.maxMessages()) {
            return LlmGatewayAdmissionResult.rejected("TOO_MANY_MESSAGES", "LLM task request has too many messages");
        }
        int messageCharacters = request.messages().stream().mapToInt(message -> message.content().length()).sum();
        if (messageCharacters > policy.maxMessageCharacters()) {
            return LlmGatewayAdmissionResult.rejected("MESSAGES_TOO_LARGE", "LLM task request messages are too large");
        }
        if (request.dynamicFacts().size() > policy.maxDynamicFacts()) {
            return LlmGatewayAdmissionResult.rejected("TOO_MANY_DYNAMIC_FACTS", "LLM task request has too many dynamic facts");
        }
        int factCharacters = request.dynamicFacts().stream().mapToInt(String::length).sum();
        if (factCharacters > policy.maxDynamicFactCharacters()) {
            return LlmGatewayAdmissionResult.rejected("DYNAMIC_FACTS_TOO_LARGE", "LLM task request dynamic facts are too large");
        }
        if (pendingCountForSource >= policy.maxPendingTasksPerSource()) {
            return LlmGatewayAdmissionResult.rejected("SOURCE_QUEUE_FULL", "LLM task source queue is full");
        }
        int maxTokens = request.maxTokens() <= 0 ? policy.defaultMaxTokens() : request.maxTokens();
        LlmGatewayRequest normalized = new LlmGatewayRequest(
                request.taskId(),
                request.purpose(),
                request.usageKind(),
                request.sourceId(),
                request.traceId(),
                request.messages(),
                request.dynamicFacts(),
                request.taskPriority(),
                request.taskPreemptible(),
                request.stream(),
                request.thinking(),
                request.useRag(),
                maxTokens,
                request.temperature(),
                request.ragRouting(),
                request.authorization(),
                request.expireAtMillis(),
                request.createdAtMillis()
        );
        return LlmGatewayAdmissionResult.accepted(normalized);
    }
}
