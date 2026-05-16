package com.rheinmetal.tianshu.function.llm.inference;

public record LlmInvocationMessage(LlmMessageRole role, String content) {
    public LlmInvocationMessage {
        role = LlmMessageRole.normalize(role);
        content = content == null ? "" : content;
    }

    public static LlmInvocationMessage system(String content) {
        return new LlmInvocationMessage(LlmMessageRole.SYSTEM, content);
    }

    public static LlmInvocationMessage user(String content) {
        return new LlmInvocationMessage(LlmMessageRole.USER, content);
    }

    public static LlmInvocationMessage assistant(String content) {
        return new LlmInvocationMessage(LlmMessageRole.ASSISTANT, content);
    }
}
