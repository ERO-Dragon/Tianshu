package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;

public record AXPromptAssembly(
        List<LLMPromptRequestPayload.MessageItemPayload> messages,
        List<LLMPromptRequestPayload.ChunkPayload> ragChunks
) {
    public AXPromptAssembly {
        messages = messages == null ? List.of() : List.copyOf(messages);
        ragChunks = ragChunks == null ? List.of() : List.copyOf(ragChunks);
    }

    public static AXPromptAssembly empty() {
        return new AXPromptAssembly(List.of(), List.of());
    }
}
