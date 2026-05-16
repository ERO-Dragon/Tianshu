package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagContext;

import java.util.List;

public record AssistantMessagePlan(List<LlmInvocationMessage> messages, LlmRagContext ragContext) {
    public AssistantMessagePlan {
        messages = messages == null || messages.isEmpty() ? List.of(LlmInvocationMessage.user("")) : List.copyOf(messages);
        ragContext = ragContext == null ? LlmRagContext.EMPTY : ragContext;
    }
}
