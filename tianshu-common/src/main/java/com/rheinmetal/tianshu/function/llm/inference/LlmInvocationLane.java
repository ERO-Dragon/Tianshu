package com.rheinmetal.tianshu.function.llm.inference;

public enum LlmInvocationLane {
    CHAT("chat"),
    TASK("task");

    private final String wireName;

    LlmInvocationLane(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
