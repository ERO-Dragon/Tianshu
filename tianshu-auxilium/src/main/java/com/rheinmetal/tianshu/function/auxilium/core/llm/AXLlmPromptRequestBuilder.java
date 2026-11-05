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
        // 普通对话不需要长思维链：关闭增强时用最低档（通常就是非思考），开启时才抬到次低档。
        Integer reasoningTier = assistantSettings.chatThinkingEnabled() ? 1 : 0;
        return new LLMPromptRequestPayload(
                request == null ? "AX.request" : request.requestKey(),
                assembly.messages(),
                true,
                false,
                0,
                reasoningTier,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}
