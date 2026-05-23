package com.rheinmetal.tianshu.function.auxilium.runtime;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactCollector;
import com.rheinmetal.tianshu.function.auxilium.fact.RuntimeFactRefreshResult;
import com.rheinmetal.tianshu.function.auxilium.memory.AXCompressionTaskDispatcher;
import com.rheinmetal.tianshu.function.auxilium.memory.AXMemorySystem;
import com.rheinmetal.tianshu.function.auxilium.memory.MemoryConsolidationPlan;
import com.rheinmetal.tianshu.function.auxilium.memory.MemoryConsolidationPlanner;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

public final class AXRuntimeMaintenanceCoordinator {
    private final RuntimeFactCollector factCollector;
    private final AXMemorySystem memorySystem;
    private final AXCompressionTaskDispatcher compressionTaskDispatcher;
    private final MemoryConsolidationPlanner memoryConsolidationPlanner;
    private final AXRuntimeMaintenancePolicy policy;
    private volatile long lastRuntimeFactRefreshAt;
    private volatile long lastMemoryConsolidationAt;

    public AXRuntimeMaintenanceCoordinator(
            RuntimeFactCollector factCollector,
            AXMemorySystem memorySystem,
            AXCompressionTaskDispatcher compressionTaskDispatcher,
            MemoryConsolidationPlanner memoryConsolidationPlanner,
            AXRuntimeMaintenancePolicy policy
    ) {
        this.factCollector = factCollector;
        this.memorySystem = memorySystem;
        this.compressionTaskDispatcher = compressionTaskDispatcher;
        this.memoryConsolidationPlanner = memoryConsolidationPlanner == null ? new MemoryConsolidationPlanner() : memoryConsolidationPlanner;
        this.policy = policy == null ? AXRuntimeMaintenancePolicy.DEFAULT : policy;
    }

    public AXRuntimeMaintenanceResult beforeQuestion(AXScope scope, AXRequest request) {
        if (scope == null || !scope.writable()) {
            return AXRuntimeMaintenanceResult.skipped();
        }
        long now = System.currentTimeMillis();
        boolean runtimeFactRefreshRun = policy.shouldRefreshRuntimeFacts(lastRuntimeFactRefreshAt, now);
        RuntimeFactRefreshResult factRefreshResult = runtimeFactRefreshRun && factCollector != null
                ? factCollector.refreshForQuestion(scope, request)
                : RuntimeFactRefreshResult.skipped();
        if (runtimeFactRefreshRun) {
            lastRuntimeFactRefreshAt = now;
        }
        boolean memoryConsolidationRun = policy.shouldConsolidateMemory(lastMemoryConsolidationAt, now);
        MemoryConsolidationPlan memoryPlan = memoryConsolidationRun && memorySystem != null
                ? memorySystem.consolidatePendingMemory(scope, memoryConsolidationPlanner)
                : new MemoryConsolidationPlan(null, null, null);
        if (memoryConsolidationRun) {
            lastMemoryConsolidationAt = now;
        }
        if (compressionTaskDispatcher != null) {
            compressionTaskDispatcher.dispatchNext(scope);
        }
        if (memorySystem != null && memoryConsolidationRun) {
            memorySystem.cleanupLongTermMemory(scope);
        }
        return new AXRuntimeMaintenanceResult(
                runtimeFactRefreshRun,
                factRefreshResult.providers(),
                factRefreshResult.producedFacts(),
                factRefreshResult.changedFacts(),
                memoryConsolidationRun && memorySystem != null,
                memoryPlan.accepted().size(),
                memoryPlan.deferred().size(),
                memoryPlan.rejected().size()
        );
    }
}
