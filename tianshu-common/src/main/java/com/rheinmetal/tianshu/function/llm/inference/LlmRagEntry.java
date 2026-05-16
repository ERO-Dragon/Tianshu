package com.rheinmetal.tianshu.function.llm.inference;

public record LlmRagEntry(String text) {
    public LlmRagEntry {
        text = text == null ? "" : text.trim();
    }

    public boolean isEmpty() {
        return text.isBlank();
    }
}
