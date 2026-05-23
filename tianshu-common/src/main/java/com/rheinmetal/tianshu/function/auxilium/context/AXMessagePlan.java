package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagContext;

import java.util.List;

public record AXMessagePlan(List<LlmInvocationMessage> messages, LlmRagContext ragContext) {
    public AXMessagePlan {
        messages = messages == null || messages.isEmpty() ? List.of(LlmInvocationMessage.user("")) : List.copyOf(messages);
        ragContext = ragContext == null ? LlmRagContext.EMPTY : ragContext;
    }
}
