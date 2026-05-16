package com.rheinmetal.tianshu.function.llm.inference;

public enum LlmMessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String wireName;

    LlmMessageRole(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static LlmMessageRole normalize(LlmMessageRole role) {
        return role == null ? USER : role;
    }
}
