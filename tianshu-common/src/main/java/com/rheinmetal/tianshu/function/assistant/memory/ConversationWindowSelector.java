package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.context.AssistantMemoryWindowPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConversationWindowSelector {
    private final AssistantMemoryWindowPolicy policy;

    public ConversationWindowSelector(AssistantMemoryWindowPolicy policy) {
        this.policy = policy == null ? AssistantMemoryWindowPolicy.DEFAULT : policy;
    }

    public List<ConversationTurn> selectRecentRawTurns(List<ConversationTurn> rawTurns) {
        if (rawTurns == null || rawTurns.isEmpty() || policy.recentRawChatTokenBudget() <= 0) {
            return List.of();
        }
        List<ConversationTurn> selected = new ArrayList<>();
        int tokens = 0;
        for (int i = rawTurns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = rawTurns.get(i);
            if (turn == null || turn.isEmpty()) {
                continue;
            }
            if (!selected.isEmpty() && tokens + turn.estimatedTokens() > policy.recentRawChatTokenBudget()) {
                break;
            }
            selected.add(turn);
            tokens += turn.estimatedTokens();
            if (tokens >= policy.recentRawChatTokenBudget()) {
                break;
            }
        }
        Collections.reverse(selected);
        return List.copyOf(selected);
    }
}
