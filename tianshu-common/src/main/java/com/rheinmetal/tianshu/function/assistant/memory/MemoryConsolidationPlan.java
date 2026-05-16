package com.rheinmetal.tianshu.function.assistant.memory;

import com.rheinmetal.tianshu.function.assistant.output.MemoryUpdateCandidate;
import com.rheinmetal.tianshu.function.assistant.output.MemoryUpdateTarget;

import java.util.List;

public record MemoryConsolidationPlan(
        List<MemoryUpdateCandidate> accepted,
        List<MemoryUpdateCandidate> deferred,
        List<MemoryUpdateCandidate> rejected
) {
    public MemoryConsolidationPlan {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        deferred = deferred == null ? List.of() : List.copyOf(deferred);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    public List<MemoryUpdateCandidate> acceptedFor(MemoryUpdateTarget target) {
        if (target == null) {
            return List.of();
        }
        return accepted.stream().filter(candidate -> candidate.target() == target).toList();
    }

    public boolean isEmpty() {
        return accepted.isEmpty() && deferred.isEmpty() && rejected.isEmpty();
    }
}
