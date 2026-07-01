package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssembly;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.List;
import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;

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
