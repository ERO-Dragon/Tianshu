package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractDirtyRuntimeFactProvider implements RuntimeFactProvider {
    private final Map<String, CapturedSnapshot> snapshotsByWorld = new ConcurrentHashMap<>();

    @Override
    public final RuntimeFactBatch refreshForQuestion(AXScope scope, AXRequest request) {
        if (scope == null || !scope.writable()) {
            return RuntimeFactBatch.unchanged(providerId());
        }
        String signature = normalizeSignature(snapshotSignature(scope, request));
        long now = System.currentTimeMillis();
        CapturedSnapshot snapshot = snapshotsByWorld.get(scope.worldId());
        if (snapshot != null && snapshot.clean(signature, now)) {
            return RuntimeFactBatch.unchanged(providerId());
        }
        List<RuntimeFact> facts = collectFacts(scope, request);
        List<RuntimeFact> normalizedFacts = facts == null ? List.of() : facts.stream().filter(Objects::nonNull).toList();
        snapshotsByWorld.put(scope.worldId(), new CapturedSnapshot(signature, normalizedFacts));
        return RuntimeFactBatch.refreshed(providerId(), normalizedFacts);
    }

    protected abstract String snapshotSignature(AXScope scope, AXRequest request);

    protected abstract List<RuntimeFact> collectFacts(AXScope scope, AXRequest request);

    protected void markDirty(AXScope scope) {
        if (scope != null) {
            snapshotsByWorld.remove(scope.worldId());
        }
    }

    private String normalizeSignature(String value) {
        return value == null ? "" : value;
    }

    private record CapturedSnapshot(String signature, List<RuntimeFact> facts) {
        private CapturedSnapshot {
            signature = signature == null ? "" : signature;
            facts = facts == null ? List.of() : List.copyOf(facts);
        }

        private boolean clean(String currentSignature, long now) {
            if (!Objects.equals(signature, currentSignature)) {
                return false;
            }
            for (RuntimeFact fact : facts) {
                if (fact != null && fact.isExpired(now)) {
                    return false;
                }
            }
            return true;
        }
    }
}
