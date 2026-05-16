package com.rheinmetal.tianshu.function.assistant.context;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptPlanner;
import com.rheinmetal.tianshu.function.assistant.prompt.AssistantPromptRenderer;
import com.rheinmetal.tianshu.function.assistant.rag.DynamicRagPackageBuilder;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagContext;

import java.util.ArrayList;
import java.util.List;

public final class AssistantContextOrchestrator {
    private final AssistantContextBudget budget;
    private final AssistantPromptPlanner promptPlanner;
    private final AssistantPromptRenderer promptRenderer;
    private final DynamicRagPackageBuilder ragPackageBuilder;

    public AssistantContextOrchestrator(AssistantContextBudget budget) {
        this(budget, new AssistantPromptPlanner(), new AssistantPromptRenderer(), new DynamicRagPackageBuilder(budget));
    }

    public AssistantContextOrchestrator(AssistantContextBudget budget, AssistantPromptPlanner promptPlanner, AssistantPromptRenderer promptRenderer) {
        this(budget, promptPlanner, promptRenderer, new DynamicRagPackageBuilder(budget));
    }

    public AssistantContextOrchestrator(
            AssistantContextBudget budget,
            AssistantPromptPlanner promptPlanner,
            AssistantPromptRenderer promptRenderer,
            DynamicRagPackageBuilder ragPackageBuilder
    ) {
        this.budget = budget == null ? AssistantContextBudget.DEFAULT : budget;
        this.promptPlanner = promptPlanner == null ? new AssistantPromptPlanner() : promptPlanner;
        this.promptRenderer = promptRenderer == null ? new AssistantPromptRenderer() : promptRenderer;
        this.ragPackageBuilder = ragPackageBuilder == null ? new DynamicRagPackageBuilder(this.budget) : ragPackageBuilder;
    }

    public AssistantMessagePlan buildMessages(AssistantRequest request, AssistantContextSnapshot context) {
        List<LlmInvocationMessage> messages = new ArrayList<>();
        String system = promptRenderer.renderSystemPrompt(promptPlanner.plan(request, context, budget), budget);
        messages.add(LlmInvocationMessage.system(system));
        addShortTerm(messages, context);
        messages.add(LlmInvocationMessage.user(request == null ? "" : request.userText()));
        List<String> dynamicRag = context == null ? List.of() : ragPackageBuilder.buildRequestPackage(context.dynamicRagCandidates());
        return new AssistantMessagePlan(messages, LlmRagContext.dynamic(dynamicRag));
    }

    private void addShortTerm(List<LlmInvocationMessage> messages, AssistantContextSnapshot context) {
        if (context == null) {
            return;
        }
        List<com.rheinmetal.tianshu.function.assistant.memory.ConversationTurn> turns = context.memory().shortTermTurns();
        int from = Math.max(0, turns.size() - budget.maxShortTermTurns());
        for (com.rheinmetal.tianshu.function.assistant.memory.ConversationTurn turn : turns.subList(from, turns.size())) {
            if (turn.isEmpty()) {
                continue;
            }
            if ("assistant".equals(turn.role())) {
                messages.add(LlmInvocationMessage.assistant(turn.content()));
            } else if ("system".equals(turn.role())) {
                messages.add(LlmInvocationMessage.system(turn.content()));
            } else {
                messages.add(LlmInvocationMessage.user(turn.content()));
            }
        }
    }
}
