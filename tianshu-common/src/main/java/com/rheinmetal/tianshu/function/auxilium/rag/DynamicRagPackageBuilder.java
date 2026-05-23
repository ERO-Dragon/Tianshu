package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.context.AXContextBudget;

import java.util.Comparator;
import java.util.List;

public final class DynamicRagPackageBuilder {
    private final AXContextBudget budget;

    public DynamicRagPackageBuilder(AXContextBudget budget) {
        this.budget = budget == null ? AXContextBudget.DEFAULT : budget;
    }

    public List<String> buildRequestPackage(List<DynamicRagCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return candidates.stream()
                .filter(candidate -> candidate != null && !candidate.isEmpty())
                .filter(candidate -> !candidate.isExpired(now))
                .filter(DynamicRagCandidate::shouldIncludeInRequestPackage)
                .sorted(Comparator.comparingInt(DynamicRagCandidate::priority).reversed().thenComparing(DynamicRagCandidate::updatedAt).reversed())
                .map(DynamicRagCandidate::text)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .limit(budget.maxDynamicRagItems())
                .toList();
    }
}
