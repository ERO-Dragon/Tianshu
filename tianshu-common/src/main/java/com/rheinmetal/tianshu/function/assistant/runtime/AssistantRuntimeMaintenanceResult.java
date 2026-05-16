package com.rheinmetal.tianshu.function.assistant.runtime;

public record AssistantRuntimeMaintenanceResult(
        boolean runtimeFactRefreshRun,
        int refreshedRuntimeFactProviders,
        int producedRuntimeFacts,
        int changedRuntimeFacts,
        boolean memoryConsolidationRun,
        int acceptedMemoryCandidates,
        int deferredMemoryCandidates,
        int rejectedMemoryCandidates
) {
    public AssistantRuntimeMaintenanceResult {
        refreshedRuntimeFactProviders = Math.max(0, refreshedRuntimeFactProviders);
        producedRuntimeFacts = Math.max(0, producedRuntimeFacts);
        changedRuntimeFacts = Math.max(0, changedRuntimeFacts);
        acceptedMemoryCandidates = Math.max(0, acceptedMemoryCandidates);
        deferredMemoryCandidates = Math.max(0, deferredMemoryCandidates);
        rejectedMemoryCandidates = Math.max(0, rejectedMemoryCandidates);
    }

    public static AssistantRuntimeMaintenanceResult skipped() {
        return new AssistantRuntimeMaintenanceResult(false, 0, 0, 0, false, 0, 0, 0);
    }

    public boolean changedAny() {
        return changedRuntimeFacts > 0 || acceptedMemoryCandidates > 0 || rejectedMemoryCandidates > 0;
    }
}
