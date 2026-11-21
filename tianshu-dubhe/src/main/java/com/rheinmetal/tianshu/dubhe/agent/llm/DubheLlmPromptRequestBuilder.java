package com.rheinmetal.tianshu.dubhe.agent.llm;

import com.rheinmetal.tianshu.dubhe.DubheAssistantSettings;
import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextBudget;
import com.rheinmetal.tianshu.dubhe.agent.context.DubheContextSnapshot;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptAssembly;
import com.rheinmetal.tianshu.dubhe.dialogue.prompt.DubhePromptOrchestrator;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptRequestPayload;

import java.util.List;
import java.util.Objects;

public final class DubheLlmPromptRequestBuilder {
    private final DubhePromptOrchestrator promptOrchestrator;
    private final DubheAssistantSettings assistantSettings;

    public DubheLlmPromptRequestBuilder(DubhePromptOrchestrator promptOrchestrator) {
        this(promptOrchestrator, DubheAssistantSettings.DEFAULT);
    }

    public DubheLlmPromptRequestBuilder(DubhePromptOrchestrator promptOrchestrator, DubheAssistantSettings assistantSettings) {
        this.promptOrchestrator = Objects.requireNonNull(promptOrchestrator, "promptOrchestrator");
        this.assistantSettings = assistantSettings == null ? DubheAssistantSettings.DEFAULT : assistantSettings;
    }

    public LLMPromptRequestPayload buildChatRequest(DubheRequest request, DubheContextSnapshot context, DubheContextBudget budget) {
        DubheContextBudget effectiveBudget = budget == null ? DubheContextBudget.DEFAULT : budget;
        DubhePromptAssembly assembly = promptOrchestrator.assemble(request, context, effectiveBudget);
        // 普通对话不需要长思维链：关闭增强时用最低档（通常就是非思考），开启时才抬到次低档。
        Integer reasoningTier = assistantSettings.chatThinkingEnabled() ? 1 : 0;
        return new LLMPromptRequestPayload(
                request == null ? "DUBHE.request" : request.requestKey(),
                assembly.messages(),
                true,
                false,
                0,
                reasoningTier,
                LLMPromptRequestPayload.SamplingPayload.empty()
        );
    }
}
