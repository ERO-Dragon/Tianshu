package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.AuxiliumAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssembly;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;
import java.util.Objects;

public final class AXLlmPromptRequestBuilder {
    private final AXPromptOrchestrator promptOrchestrator;
    private final AuxiliumAssistantSettings assistantSettings;

    public AXLlmPromptRequestBuilder(AXPromptOrchestrator promptOrchestrator) {
        this(promptOrchestrator, AuxiliumAssistantSettings.DEFAULT);
    }

    public AXLlmPromptRequestBuilder(AXPromptOrchestrator promptOrchestrator, AuxiliumAssistantSettings assistantSettings) {
        this.promptOrchestrator = Objects.requireNonNull(promptOrchestrator, "promptOrchestrator");
        this.assistantSettings = assistantSettings == null ? AuxiliumAssistantSettings.DEFAULT : assistantSettings;
    }

    public LLMPromptRequestPayload buildChatRequest(AXRequest request, AXContextSnapshot context, AXContextBudget budget) {
        AXContextBudget effectiveBudget = budget == null ? AXContextBudget.DEFAULT : budget;
        AXPromptAssembly assembly = promptOrchestrator.assemble(request, context, effectiveBudget);
        List<LLMPromptRequestPayload.ChunkPayload> chunks = List.of(LLMPromptRequestPayload.ChunkPayload.message(assembly.messages()));
        return new LLMPromptRequestPayload(
                request == null ? "AX.request" : request.requestKey(),
                0,
                null,
                true,
                assistantSettings.chatThinkingEnabled(),
                "CHAT",
                0,
                false,
                chunks
        );
    }
}
