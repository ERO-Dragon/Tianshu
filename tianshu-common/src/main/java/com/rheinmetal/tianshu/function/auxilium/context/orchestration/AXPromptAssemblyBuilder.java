package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.ArrayList;
import java.util.List;

public final class AXPromptAssemblyBuilder {
    private final List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
    private final List<LLMPromptRequestPayload.ChunkPayload> ragChunks = new ArrayList<>();

    public void addSystemMessage(String content) {
        addMessage("system", content);
    }

    public void addUserMessage(String content) {
        addMessage("user", content);
    }

    public void addAssistantMessage(String content) {
        addMessage("assistant", content);
    }

    public void addMessage(String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.of(role, content));
    }

    public void addRagChunk(LLMPromptRequestPayload.ChunkPayload chunk) {
        if (chunk == null) {
            return;
        }
        ragChunks.add(chunk);
    }

    public AXPromptAssembly build() {
        return new AXPromptAssembly(messages, ragChunks);
    }
}
