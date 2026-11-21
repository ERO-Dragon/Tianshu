package com.rheinmetal.tianshu.dubhe.core.prompt;

import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;

import java.util.List;

public record DubhePromptAssembly(
        List<LLMPromptRequestPayload.MessageItemPayload> messages
) {
    public DubhePromptAssembly {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public static DubhePromptAssembly empty() {
        return new DubhePromptAssembly(List.of());
    }
}
