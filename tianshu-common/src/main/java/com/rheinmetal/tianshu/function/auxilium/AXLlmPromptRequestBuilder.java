package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.context.orchestration.AXPromptAssembly;
import com.rheinmetal.tianshu.function.auxilium.context.orchestration.AXPromptOrchestrator;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;
import java.util.Objects;

public final class AXLlmPromptRequestBuilder {
    private final AXPromptOrchestrator promptOrchestrator;
    private final AXContextBudget budget;

    public AXLlmPromptRequestBuilder(AXPromptOrchestrator promptOrchestrator, AXContextBudget budget) {
        this.promptOrchestrator = Objects.requireNonNull(promptOrchestrator, "promptOrchestrator");
        this.budget = budget == null ? AXContextBudget.DEFAULT : budget;
    }

    public LLMPromptRequestPayload buildChatRequest(AXRequest request, AXContextSnapshot context) {
        AXPromptAssembly assembly = promptOrchestrator.assemble(request, context, budget);
        List<LLMPromptRequestPayload.ChunkPayload> chunks = List.of(LLMPromptRequestPayload.ChunkPayload.message(assembly.messages()));
        return new LLMPromptRequestPayload(
                request == null ? "AX.request" : request.requestKey(),
                0,
                0.7f,
                true,
                false,
                "CHAT",
                0,
                false,
                chunks
        );
    }
}
