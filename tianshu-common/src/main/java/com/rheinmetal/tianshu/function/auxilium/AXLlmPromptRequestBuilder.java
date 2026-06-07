package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;
import com.rheinmetal.tianshu.function.auxilium.context.AXContextSnapshot;
import com.rheinmetal.tianshu.function.auxilium.memory.ConversationTurn;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptPlan;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptPlanner;
import com.rheinmetal.tianshu.function.auxilium.prompt.AXPromptRenderer;
import com.rheinmetal.tianshu.function.auxilium.rag.DynamicRagCandidate;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptRequestPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class AXLlmPromptRequestBuilder {
    private final AXPromptPlanner promptPlanner;
    private final AXPromptRenderer promptRenderer;
    private final AXContextBudget budget;

    public AXLlmPromptRequestBuilder(AXPromptPlanner promptPlanner, AXPromptRenderer promptRenderer, AXContextBudget budget) {
        this.promptPlanner = Objects.requireNonNull(promptPlanner, "promptPlanner");
        this.promptRenderer = Objects.requireNonNull(promptRenderer, "promptRenderer");
        this.budget = budget == null ? AXContextBudget.DEFAULT : budget;
    }

    public LLMPromptRequestPayload buildChatRequest(AXRequest request, AXContextSnapshot context) {
        AXPromptPlan promptPlan = promptPlanner.plan(request, context, budget);
        List<LLMPromptRequestPayload.ChunkPayload> chunks = new ArrayList<>();
        chunks.add(LLMPromptRequestPayload.ChunkPayload.message(messageItems(request, context, promptRenderer.renderSystemPrompt(promptPlan, budget))));
        dynamicRagChunk(context).stream().findFirst().ifPresent(chunks::add);
        return new LLMPromptRequestPayload(
                request == null ? "AX.request" : request.requestKey(),
                1024,
                0.7f,
                true,
                false,
                "CHAT",
                0,
                false,
                chunks
        );
    }

    private List<LLMPromptRequestPayload.MessageItemPayload> messageItems(AXRequest request, AXContextSnapshot context, String systemPrompt) {
        List<LLMPromptRequestPayload.MessageItemPayload> messages = new ArrayList<>();
        messages.add(LLMPromptRequestPayload.MessageItemPayload.system(systemPrompt));
        if (context != null && context.memory() != null) {
            context.memory().shortTermTurns().stream()
                    .filter(turn -> turn != null && !turn.isEmpty())
                    .skip(Math.max(0, context.memory().shortTermTurns().size() - budget.maxShortTermTurns()))
                    .map(this::toMessage)
                    .forEach(messages::add);
        }
        messages.add(LLMPromptRequestPayload.MessageItemPayload.user(request == null ? "" : request.userText()));
        return List.copyOf(messages);
    }

    private LLMPromptRequestPayload.MessageItemPayload toMessage(ConversationTurn turn) {
        String role = "AX".equals(turn.role()) ? "assistant" : "user";
        return LLMPromptRequestPayload.MessageItemPayload.of(role, turn.content());
    }

    private List<LLMPromptRequestPayload.ChunkPayload> dynamicRagChunk(AXContextSnapshot context) {
        if (context == null || context.dynamicRagCandidates().isEmpty() || budget.maxDynamicRagItems() <= 0) {
            return List.of();
        }
        List<String> texts = context.dynamicRagCandidates().stream()
                .filter(candidate -> candidate != null && !candidate.isEmpty() && candidate.shouldIncludeInRequestPackage())
                .sorted(Comparator.comparingInt(DynamicRagCandidate::priority).reversed())
                .limit(budget.maxDynamicRagItems())
                .map(DynamicRagCandidate::text)
                .toList();
        if (texts.isEmpty()) {
            return List.of();
        }
        String uid = "ax.dynamic." + AXStorageSafeName.safe(context.scope().worldId());
        return List.of(LLMPromptRequestPayload.ChunkPayload.rag(uid, "Runtime context:", texts, true, true, 1000));
    }

    private static final class AXStorageSafeName {
        private AXStorageSafeName() {
        }

        private static String safe(String value) {
            if (value == null || value.isBlank()) {
                return "unknown";
            }
            return value.replaceAll("[^a-zA-Z0-9._-]", "_");
        }
    }
}
