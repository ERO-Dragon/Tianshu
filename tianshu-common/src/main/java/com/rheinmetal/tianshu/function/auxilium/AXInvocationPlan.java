package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationRequest;

public record AXInvocationPlan(AXRequest AXRequest, AXScope scope, LlmInvocationRequest invocationRequest) {
    public AXInvocationPlan {
        if (AXRequest == null) {
            AXRequest = new AXRequest("AX.request", "", "");
        }
        if (scope == null) {
            scope = AXScope.unknown();
        }
        if (invocationRequest == null) {
            invocationRequest = LlmInvocationRequest.streaming(AXRequest.requestKey(), java.util.List.of());
        }
    }
}
