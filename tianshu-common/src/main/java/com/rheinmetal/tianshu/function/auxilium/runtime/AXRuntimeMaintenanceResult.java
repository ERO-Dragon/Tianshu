package com.rheinmetal.tianshu.function.auxilium.runtime;

public record AXRuntimeMaintenanceResult(
        boolean runtimeFactRefreshRun,
        int refreshedRuntimeFactProviders,
        int producedRuntimeFacts,
        int changedRuntimeFacts,
        boolean memoryConsolidationRun,
        int acceptedMemoryCandidates,
        int deferredMemoryCandidates,
        int rejectedMemoryCandidates
) {
    public AXRuntimeMaintenanceResult {
        refreshedRuntimeFactProviders = Math.max(0, refreshedRuntimeFactProviders);
        producedRuntimeFacts = Math.max(0, producedRuntimeFacts);
        changedRuntimeFacts = Math.max(0, changedRuntimeFacts);
        acceptedMemoryCandidates = Math.max(0, acceptedMemoryCandidates);
        deferredMemoryCandidates = Math.max(0, deferredMemoryCandidates);
        rejectedMemoryCandidates = Math.max(0, rejectedMemoryCandidates);
    }

    public static AXRuntimeMaintenanceResult skipped() {
        return new AXRuntimeMaintenanceResult(false, 0, 0, 0, false, 0, 0, 0);
    }

    public boolean changedAny() {
        return changedRuntimeFacts > 0 || acceptedMemoryCandidates > 0 || rejectedMemoryCandidates > 0;
    }
}
