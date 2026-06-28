package com.rheinmetal.tianshu.function.auxilium.context.orchestration;

import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;

public record AXPromptAssembly(
        List<LLMPromptRequestPayload.MessageItemPayload> messages
) {
    public AXPromptAssembly {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static AXPromptAssembly empty() {
        return new AXPromptAssembly(List.of());
    }
}
