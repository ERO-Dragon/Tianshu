package com.rheinmetal.tianshu.function.assistant.fact;

import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeFactPool {
    private final Map<String, Map<String, RuntimeFact>> factsByWorld = new ConcurrentHashMap<>();

    public boolean upsert(AssistantScope scope, RuntimeFact fact) {
        if (scope == null || !scope.writable() || fact == null || fact.isEmpty()) {
            return false;
        }
        Map<String, RuntimeFact> facts = factsByWorld.computeIfAbsent(scope.worldId(), ignored -> new ConcurrentHashMap<>());
        RuntimeFact current = facts.get(fact.factId());
        if (current != null && fact.version() < current.version()) {
            return false;
        }
        facts.put(fact.factId(), fact);
        return true;
    }

    public int upsertAll(AssistantScope scope, List<RuntimeFact> facts) {
        if (facts == null) {
            return 0;
        }
        int changed = 0;
        for (RuntimeFact fact : facts) {
            if (upsert(scope, fact)) {
                changed++;
            }
        }
        return changed;
    }

    public List<RuntimeFact> snapshot(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return List.of();
        }
        pruneExpired(scope);
        Map<String, RuntimeFact> facts = factsByWorld.get(scope.worldId());
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        return facts.values().stream()
                .sorted(Comparator.comparingInt(RuntimeFact::importance).reversed().thenComparing(RuntimeFact::updatedAt).reversed())
                .toList();
    }

    public void pruneExpired(AssistantScope scope) {
        if (scope == null || !scope.writable()) {
            return;
        }
        Map<String, RuntimeFact> facts = factsByWorld.get(scope.worldId());
        if (facts == null) {
            return;
        }
        long now = System.currentTimeMillis();
        facts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
}
