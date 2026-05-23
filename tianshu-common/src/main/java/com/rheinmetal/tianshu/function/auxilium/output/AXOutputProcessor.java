package com.rheinmetal.tianshu.function.auxilium.output;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.ConversationTurn;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagHit;

import java.util.List;

public final class AXOutputProcessor {
    private static final int MAX_TURN_CHARS = 8000;
    private final AXMemorySystem memorySystem;
    private final MemoryUpdatePlanner memoryUpdatePlanner;

    public AXOutputProcessor(AXMemorySystem memorySystem) {
        this(memorySystem, new MemoryUpdatePlanner());
    }

    public AXOutputProcessor(AXMemorySystem memorySystem, MemoryUpdatePlanner memoryUpdatePlanner) {
        this.memorySystem = memorySystem;
        this.memoryUpdatePlanner = memoryUpdatePlanner == null ? new MemoryUpdatePlanner() : memoryUpdatePlanner;
    }

    public void recordUserInput(AXScope scope, AXRequest request) {
        if (memorySystem == null || request == null || request.userText().isBlank()) {
            return;
        }
        String userText = normalizeTurnText(request.userText());
        if (userText.isBlank()) {
            return;
        }
        memorySystem.appendConversationTurn(scope, new ConversationTurn("user", userText, System.currentTimeMillis()));
    }

    public void recordAXOutput(AXScope scope, AXRequest request, String text) {
        if (memorySystem == null) {
            return;
        }
        String AXText = normalizeTurnText(text);
        if (AXText.isBlank()) {
            return;
        }
        memorySystem.appendConversationTurn(scope, new ConversationTurn("AX", AXText, System.currentTimeMillis()));
        if (request != null) {
            memorySystem.appendMemoryUpdateCandidates(scope, memoryUpdatePlanner.plan(request.requestKey(), request.userText(), AXText));
        }
    }

    public void recordAXOutput(AXScope scope, String text) {
        recordAXOutput(scope, null, text);
    }

    public void recordRagHits(AXScope scope, List<LlmRagHit> hits) {
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
