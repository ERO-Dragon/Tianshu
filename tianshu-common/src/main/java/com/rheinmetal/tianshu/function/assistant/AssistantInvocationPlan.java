package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;

public record AssistantInvocationPlan(AssistantRequest assistantRequest, AssistantScope scope, LlmInvocationRequest invocationRequest) {
    public AssistantInvocationPlan {
        if (assistantRequest == null) {
            assistantRequest = new AssistantRequest("assistant.request", "", "");
        }
        if (scope == null) {
            scope = AssistantScope.unknown();
        }
        if (invocationRequest == null) {
            invocationRequest = LlmInvocationRequest.streaming(assistantRequest.requestKey(), java.util.List.of());
        }
    }
}
