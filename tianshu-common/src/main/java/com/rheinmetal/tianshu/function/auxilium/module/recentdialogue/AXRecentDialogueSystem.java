package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.core.prompt.AXTokenCounter;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;
import java.util.OptionalInt;

public final class AXRecentDialogueSystem {
    private final AXRawTurnWindow rawTurnWindow;
    private final AXTokenCounter tokenCounter;

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy) {
        this(policy, AXTokenCounter.unavailable());
    }

    public AXRecentDialogueSystem(AXMemoryWindowPolicy policy, AXTokenCounter tokenCounter) {
        this.rawTurnWindow = new AXRawTurnWindow(policy);
        this.tokenCounter = tokenCounter == null ? AXTokenCounter.unavailable() : tokenCounter;
    }

    public void append(AXScope scope, AXRawTurn turn) {
        rawTurnWindow.append(scope, withTokenCount(scope, turn));
    }

    public AXRecentDialogueSnapshot snapshot(AXScope scope) {
        return new AXRecentDialogueSnapshot(rawTurnWindow.recent(scope));
    }

    public AXRawTurnBatch selectCompressionBatch(AXScope scope) {
        return rawTurnWindow.selectCompressionBatch(scope);
    }

    public int confirmConsumed(AXScope scope, AXRawTurnBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        List<String> turnIds = batch.turnIds();
        if (turnIds.isEmpty()) {
            return 0;
        }
        return rawTurnWindow.confirmConsumed(scope, turnIds);
    }

    private AXRawTurn withTokenCount(AXScope scope, AXRawTurn turn) {
        if (turn == null || turn.isEmpty() || turn.tokenCount() > 0) {
            return turn;
        }
        OptionalInt count = tokenCounter.countMessageTokens(requestId(scope, turn), tokenizerRole(turn), tokenizerContent(turn));
        return count.isPresent() ? turn.withTokenCount(count.getAsInt()) : turn;
    }

    private String requestId(AXScope scope, AXRawTurn turn) {
        String worldId = scope == null || scope.worldId() == null || scope.worldId().isBlank() ? turn.worldId() : scope.worldId();
        return "ax.recent_dialogue.token." + worldId + "." + turn.id();
    }

    private String tokenizerRole(AXRawTurn turn) {
        return turn != null && turn.assistantRole() ? "assistant" : "user";
    }

    private String tokenizerContent(AXRawTurn turn) {
        if (turn == null || turn.content() == null) {
            return "";
        }
        if (turn.gameChatRole()) {
            String speaker = turn.speakerName() == null || turn.speakerName().isBlank() ? "unknown" : turn.speakerName().trim();
            return "<chat speaker=\"" + speaker + "\">" + turn.content().trim() + "</chat>";
        }
        return turn.content().trim();
    }
}
