package com.rheinmetal.tianshu.function.auxilium.module.recentdialogue;

import com.rheinmetal.tianshu.function.auxilium.core.context.AXMemoryWindowPolicy;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
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
        if (!writable(scope) || policy.recentRawDialogueTokenBudget() <= 0) {
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
        List<TurnGroup> groups = turnGroups(snapshot);
        List<TurnGroup> selectedGroups = new ArrayList<>();
        int tokens = 0;
        for (int i = groups.size() - 1; i >= 0; i--) {
            TurnGroup group = groups.get(i);
            if (!selectedGroups.isEmpty() && tokens + group.tokenCount() > policy.recentRawDialogueTokenBudget()) {
                break;
            }
            selectedGroups.add(group);
            tokens += group.tokenCount();
            if (tokens >= policy.recentRawDialogueTokenBudget()) {
                break;
            }
        }
        Collections.reverse(selectedGroups);
        return selectedGroups.stream()
                .flatMap(group -> group.turns().stream())
                .toList();
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

    public Map<String, List<AXRawTurn>> snapshotAll() {
        Map<String, List<AXRawTurn>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<AXRawTurn>> entry : turnsByWorld.entrySet()) {
            Deque<AXRawTurn> turns = entry.getValue();
            if (turns == null) {
                continue;
            }
            synchronized (turns) {
                result.put(entry.getKey(), List.copyOf(turns));
            }
        }
        return Map.copyOf(result);
    }

    public void replace(AXScope scope, List<AXRawTurn> turns) {
        if (!writable(scope)) {
            return;
        }
        Deque<AXRawTurn> next = new ArrayDeque<>();
        if (turns != null) {
            for (AXRawTurn turn : turns) {
                if (turn != null && !turn.isEmpty()) {
                    next.addLast(turn);
                }
            }
        }
        trim(next);
        Deque<AXRawTurn> existing = turnsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ArrayDeque<>());
        synchronized (existing) {
            existing.clear();
            existing.addAll(next);
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
            List<TurnGroup> groups = turnGroups(new ArrayList<>(turns));
            int totalTokens = groups.stream().mapToInt(TurnGroup::compressibleTokens).sum();
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
            for (TurnGroup group : groups) {
                if (group.compressibleTokens() <= 0) {
                    continue;
                }
                if (!selected.isEmpty() && tokens + group.compressibleTokens() > max) {
                    break;
                }
                selected.addAll(group.compressibleTurns());
                tokens += group.compressibleTokens();
                chars += group.compressibleCharacters();
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
            tokens += turn.tokenCount();
            characters += turn.characterCount();
        }
        while (!turns.isEmpty() && (tokens > policy.maxRawTokenCount() || characters > policy.maxRawCharacters())) {
            AXRawTurn removed = turns.removeFirst();
            tokens -= removed.tokenCount();
            characters -= removed.characterCount();
        }
    }

    private boolean writable(AXScope scope) {
        return scope != null && scope.writable();
    }

    private List<TurnGroup> turnGroups(List<AXRawTurn> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }
        List<TurnGroup> groups = new ArrayList<>();
        int index = 0;
        while (index < snapshot.size()) {
            AXRawTurn turn = snapshot.get(index);
            if (turn == null || turn.isEmpty()) {
                index++;
                continue;
            }
            String key = dialogueBoundaryKey(turn);
            if (key.isBlank()) {
                groups.add(new TurnGroup(List.of(turn)));
                index++;
                continue;
            }
            int end = index;
            for (int cursor = index + 1; cursor < snapshot.size(); cursor++) {
                AXRawTurn candidate = snapshot.get(cursor);
                if (candidate == null || candidate.isEmpty()) {
                    continue;
                }
                String candidateKey = dialogueBoundaryKey(candidate);
                if (key.equals(candidateKey)) {
                    end = cursor;
                    continue;
                }
                if (!candidateKey.isBlank()) {
                    break;
                }
            }
            List<AXRawTurn> groupTurns = new ArrayList<>();
            for (int cursor = index; cursor <= end; cursor++) {
                AXRawTurn candidate = snapshot.get(cursor);
                if (candidate != null && !candidate.isEmpty()) {
                    groupTurns.add(candidate);
                }
            }
            groups.add(new TurnGroup(groupTurns));
            index = end + 1;
        }
        return groups;
    }

    private String dialogueBoundaryKey(AXRawTurn turn) {
        if (turn == null || turn.iaTurnId() == null || turn.iaTurnId().isBlank()) {
            return "";
        }
        if (!"user".equals(turn.role()) && !"assistant".equals(turn.role())) {
            return "";
        }
        return turn.iaSessionId() + ":" + turn.iaTurnId();
    }

    private record TurnGroup(List<AXRawTurn> turns) {
        private TurnGroup {
            turns = turns == null ? List.of() : turns.stream()
                    .filter(turn -> turn != null && !turn.isEmpty())
                    .toList();
        }

        private int tokenCount() {
            return turns.stream().mapToInt(AXRawTurn::tokenCount).sum();
        }

        private int compressibleTokens() {
            return compressibleTurns().stream().mapToInt(AXRawTurn::tokenCount).sum();
        }

        private int compressibleCharacters() {
            return compressibleTurns().stream().mapToInt(AXRawTurn::characterCount).sum();
        }

        private List<AXRawTurn> compressibleTurns() {
            return turns.stream()
                    .filter(turn -> !"world_event".equals(turn.role()))
                    .toList();
        }
    }
}
