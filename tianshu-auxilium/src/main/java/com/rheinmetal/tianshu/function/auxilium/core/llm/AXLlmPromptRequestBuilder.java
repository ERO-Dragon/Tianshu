package com.rheinmetal.tianshu.function.auxilium.core.llm;

import com.rheinmetal.tianshu.function.auxilium.AuxiliumAssistantSettings;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.core.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptAssembly;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXPromptOrchestrator;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;

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
        // 普通对话不需要长思维链：关闭时明确要求 off，开启时也只取 lowest，把成本留给真正需要的任务。
        String reasoningEffort = assistantSettings.chatThinkingEnabled()
                ? LLMPromptRequestPayload.REASONING_LOW
                : LLMPromptRequestPayload.REASONING_OFF;
        return new LLMPromptRequestPayload(
                request == null ? "AX.request" : request.requestKey(),
                assembly.messages(),
                true,
                false,
                0,
                reasoningEffort,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}
