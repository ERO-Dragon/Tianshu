package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AXRawTurnWindow {
    private final AXMemoryWindowPolicy policy;
    private final Map<String, Deque<AXRawTurn>> turnsByWorld = new ConcurrentHashMap<>();

    public AXRawTurnWindow(AXMemoryWindowPolicy policy) {
        this.policy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
    }

    public void append(AXScope scope, AXRawTurn turn) {
        if (!writable(scope) || turn == null || turn.isEmpty()) {
            return;
        }
        Deque<AXRawTurn> turns = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(turn);
            trim(turns);
        }
    }

    public List<AXRawTurn> recent(AXScope scope) {
        if (!writable(scope) || policy.recentRawChatTokenBudget() <= 0) {
            return List.of();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        List<AXRawTurn> snapshot;
        synchronized (turns) {
            snapshot = new ArrayList<>(turns);
        }
        List<AXRawTurn> selected = new ArrayList<>();
        int tokens = 0;
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            AXRawTurn turn = snapshot.get(i);
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

    public List<AXRawTurn> snapshot(AXScope scope) {
        if (!writable(scope)) {
            return List.of();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return List.copyOf(turns);
        }
    }

    public AXRawTurnBatch selectCompressionBatch(AXScope scope) {
        if (!writable(scope) || policy.shortTermCompressTokenTarget() <= 0) {
            return AXRawTurnBatch.empty();
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return AXRawTurnBatch.empty();
        }
        synchronized (turns) {
            int totalTokens = turns.stream().mapToInt(AXRawTurn::estimatedTokens).sum();
            if (totalTokens <= policy.recentRawKeepTokenTarget()) {
                return AXRawTurnBatch.empty();
            }
            int removableBudget = Math.max(0, totalTokens - policy.recentRawKeepTokenTarget());
            int target = Math.min(policy.shortTermCompressTokenTarget(), removableBudget);
            int max = Math.min(policy.shortTermCompressTokenMax(), removableBudget);
            if (target <= 0 || max <= 0) {
                return AXRawTurnBatch.empty();
            }
            List<AXRawTurn> selected = new ArrayList<>();
            int tokens = 0;
            int chars = 0;
            for (AXRawTurn turn : turns) {
                if (turn == null || turn.isEmpty()) {
                    continue;
                }
                if (!selected.isEmpty() && tokens + turn.estimatedTokens() > max) {
                    break;
                }
                selected.add(turn);
                tokens += turn.estimatedTokens();
                chars += turn.characterCount();
                if (tokens >= target) {
                    break;
                }
            }
            return selected.isEmpty() ? AXRawTurnBatch.empty() : new AXRawTurnBatch("", selected, 0L, 0L, tokens, chars);
        }
    }

    public int confirmConsumed(AXScope scope, List<String> turnIds) {
        if (!writable(scope) || turnIds == null || turnIds.isEmpty()) {
            return 0;
        }
        Deque<AXRawTurn> turns = turnsByWorld.get(scope.worldId());
        if (turns == null) {
            return 0;
        }
        List<String> expected = turnIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (expected.isEmpty()) {
            return 0;
        }
        synchronized (turns) {
            int removed = 0;
            while (removed < expected.size() && !turns.isEmpty()) {
                AXRawTurn first = turns.peekFirst();
                if (first == null || !expected.get(removed).equals(first.id())) {
                    break;
                }
                turns.removeFirst();
                removed++;
            }
            return removed;
        }
    }

    private void trim(Deque<AXRawTurn> turns) {
        int tokens = 0;
        int characters = 0;
        for (AXRawTurn turn : turns) {
            tokens += turn.estimatedTokens();
            characters += turn.characterCount();
        }
        while (!turns.isEmpty() && (tokens > policy.maxRawEstimatedTokens() || characters > policy.maxRawCharacters())) {
            AXRawTurn removed = turns.removeFirst();
            tokens -= removed.estimatedTokens();
            characters -= removed.characterCount();
        }
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable();
    }
}
