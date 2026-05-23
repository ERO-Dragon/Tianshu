package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptPlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptRenderer;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagPackageBuilder;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationMessage;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagContext;

import java.util.ArrayList;
import java.util.List;

public final class AXContextOrchestrator {
    private final AXContextBudget budget;
    private final AXPromptPlanner promptPlanner;
    private final AXPromptRenderer promptRenderer;
    private final DynamicRagPackageBuilder ragPackageBuilder;

    public AXContextOrchestrator(AXContextBudget budget) {
        this(budget, new AXPromptPlanner(), new AXPromptRenderer(), new DynamicRagPackageBuilder(budget));
    }

    public AXContextOrchestrator(AXContextBudget budget, AXPromptPlanner promptPlanner, AXPromptRenderer promptRenderer) {
        this(budget, promptPlanner, promptRenderer, new DynamicRagPackageBuilder(budget));
    }

    public AXContextOrchestrator(
            AXContextBudget budget,
            AXPromptPlanner promptPlanner,
            AXPromptRenderer promptRenderer,
            DynamicRagPackageBuilder ragPackageBuilder
    ) {
        this.budget = budget == null ? AXContextBudget.DEFAULT : budget;
        this.promptPlanner = promptPlanner == null ? new AXPromptPlanner() : promptPlanner;
        this.promptRenderer = promptRenderer == null ? new AXPromptRenderer() : promptRenderer;
        this.ragPackageBuilder = ragPackageBuilder == null ? new DynamicRagPackageBuilder(this.budget) : ragPackageBuilder;
    }

    public AXMessagePlan buildMessages(AXRequest request, AXContextSnapshot context) {
        List<LlmInvocationMessage> messages = new ArrayList<>();
        String system = promptRenderer.renderSystemPrompt(promptPlanner.plan(request, context, budget), budget);
        messages.add(LlmInvocationMessage.system(system));
        addShortTerm(messages, context);
        messages.add(LlmInvocationMessage.user(request == null ? "" : request.userText()));
        List<String> dynamicRag = context == null ? List.of() : ragPackageBuilder.buildRequestPackage(context.dynamicRagCandidates());
        return new AXMessagePlan(messages, LlmRagContext.dynamic(dynamicRag));
    }

    private void addShortTerm(List<LlmInvocationMessage> messages, AXContextSnapshot context) {
        if (context == null) {
            return;
        }
        List<com.rheinmetal.tianshu.function.auxilium.memory.ConversationTurn> turns = context.memory().shortTermTurns();
        int from = Math.max(0, turns.size() - budget.maxShortTermTurns());
        for (com.rheinmetal.tianshu.function.auxilium.memory.ConversationTurn turn : turns.subList(from, turns.size())) {
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
