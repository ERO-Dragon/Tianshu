package com.rheinmetal.tianshu.function.llm.inference;

import java.util.List;

public record LlmInvocationRequest(String requestKey, List<LlmInvocationMessage> messages, LlmGenerationOptions options, LlmRagContext ragContext) {
    public LlmInvocationRequest {
        requestKey = requestKey == null || requestKey.isBlank() ? "llm.invocation" : requestKey.trim();
        messages = normalizeMessages(messages);
        options = options == null ? LlmGenerationOptions.DEFAULT_STREAMING : options;
        ragContext = ragContext == null ? LlmRagContext.EMPTY : ragContext;
    }

    public static LlmInvocationRequest streaming(String requestKey, List<LlmInvocationMessage> messages) {
        return new LlmInvocationRequest(requestKey, messages, LlmGenerationOptions.DEFAULT_STREAMING, LlmRagContext.EMPTY);
    }

    public static LlmInvocationRequest streaming(String requestKey, List<LlmInvocationMessage> messages, LlmRagContext ragContext) {
        return new LlmInvocationRequest(requestKey, messages, LlmGenerationOptions.DEFAULT_STREAMING, ragContext);
    }

    public static LlmInvocationRequest collecting(String requestKey, List<LlmInvocationMessage> messages) {
        return new LlmInvocationRequest(requestKey, messages, LlmGenerationOptions.DEFAULT_COLLECTING, LlmRagContext.EMPTY);
    }

    public static LlmInvocationRequest collecting(String requestKey, List<LlmInvocationMessage> messages, LlmRagContext ragContext) {
        return new LlmInvocationRequest(requestKey, messages, LlmGenerationOptions.DEFAULT_COLLECTING, ragContext);
    }

    public static LlmInvocationRequest task(String requestKey, List<LlmInvocationMessage> messages) {
        return new LlmInvocationRequest(requestKey, messages, LlmGenerationOptions.DEFAULT_TASK, LlmRagContext.EMPTY);
    }

    public LlmInvocationRequest withOptions(LlmGenerationOptions options) {
        return new LlmInvocationRequest(requestKey, messages, options, ragContext);
    }

    public LlmInvocationRequest withRagContext(LlmRagContext ragContext) {
        return new LlmInvocationRequest(requestKey, messages, options, ragContext);
    }

    private static List<LlmInvocationMessage> normalizeMessages(List<LlmInvocationMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of(LlmInvocationMessage.user(""));
        }
        List<LlmInvocationMessage> normalized = messages.stream()
                .filter(message -> message != null)
                .map(message -> new LlmInvocationMessage(message.role(), message.content()))
                .toList();
        return normalized.isEmpty() ? List.of(LlmInvocationMessage.user("")) : List.copyOf(normalized);
    }
}
