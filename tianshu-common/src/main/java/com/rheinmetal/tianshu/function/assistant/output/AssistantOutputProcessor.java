package com.rheinmetal.tianshu.function.assistant.output;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySystem;
import com.rheinmetal.tianshu.function.assistant.memory.ConversationTurn;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagHit;

import java.util.List;

public final class AssistantOutputProcessor {
    private static final int MAX_TURN_CHARS = 8000;
    private final AssistantMemorySystem memorySystem;
    private final MemoryUpdatePlanner memoryUpdatePlanner;

    public AssistantOutputProcessor(AssistantMemorySystem memorySystem) {
        this(memorySystem, new MemoryUpdatePlanner());
    }

    public AssistantOutputProcessor(AssistantMemorySystem memorySystem, MemoryUpdatePlanner memoryUpdatePlanner) {
        this.memorySystem = memorySystem;
        this.memoryUpdatePlanner = memoryUpdatePlanner == null ? new MemoryUpdatePlanner() : memoryUpdatePlanner;
    }

    public void recordUserInput(AssistantScope scope, AssistantRequest request) {
        if (memorySystem == null || request == null || request.userText().isBlank()) {
            return;
        }
        String userText = normalizeTurnText(request.userText());
        if (userText.isBlank()) {
            return;
        }
        memorySystem.appendConversationTurn(scope, new ConversationTurn("user", userText, System.currentTimeMillis()));
    }

    public void recordAssistantOutput(AssistantScope scope, AssistantRequest request, String text) {
        if (memorySystem == null) {
            return;
        }
        String assistantText = normalizeTurnText(text);
        if (assistantText.isBlank()) {
            return;
        }
        memorySystem.appendConversationTurn(scope, new ConversationTurn("assistant", assistantText, System.currentTimeMillis()));
        if (request != null) {
            memorySystem.appendMemoryUpdateCandidates(scope, memoryUpdatePlanner.plan(request.requestKey(), request.userText(), assistantText));
        }
    }

    public void recordAssistantOutput(AssistantScope scope, String text) {
        recordAssistantOutput(scope, null, text);
    }

    public void recordRagHits(AssistantScope scope, List<LlmRagHit> hits) {
        if (memorySystem == null || hits == null || hits.isEmpty()) {
            return;
        }
        List<String> memoryUids = hits.stream()
                .filter(hit -> hit != null && hit.memory())
                .map(LlmRagHit::uid)
                .distinct()
                .toList();
        if (!memoryUids.isEmpty()) {
            memorySystem.recordLongTermMemoryHits(scope, memoryUids);
        }
    }

    private String normalizeTurnText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.trim()
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n");
        return normalized.length() > MAX_TURN_CHARS ? normalized.substring(0, MAX_TURN_CHARS).trim() : normalized;
    }
}
