package com.rheinmetal.tianshu.function.assistant.runtime;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFactCollector;
import com.rheinmetal.tianshu.function.assistant.fact.RuntimeFactRefreshResult;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantCompressionTaskDispatcher;
import com.rheinmetal.tianshu.function.assistant.memory.AssistantMemorySystem;
import com.rheinmetal.tianshu.function.assistant.memory.MemoryConsolidationPlan;
import com.rheinmetal.tianshu.function.assistant.memory.MemoryConsolidationPlanner;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;

public final class AssistantRuntimeMaintenanceCoordinator {
    private final RuntimeFactCollector factCollector;
    private final AssistantMemorySystem memorySystem;
    private final AssistantCompressionTaskDispatcher compressionTaskDispatcher;
    private final MemoryConsolidationPlanner memoryConsolidationPlanner;
    private final AssistantRuntimeMaintenancePolicy policy;
    private volatile long lastRuntimeFactRefreshAt;
    private volatile long lastMemoryConsolidationAt;

    public AssistantRuntimeMaintenanceCoordinator(
            RuntimeFactCollector factCollector,
            AssistantMemorySystem memorySystem,
            AssistantCompressionTaskDispatcher compressionTaskDispatcher,
            MemoryConsolidationPlanner memoryConsolidationPlanner,
            AssistantRuntimeMaintenancePolicy policy
    ) {
        this.factCollector = factCollector;
        this.memorySystem = memorySystem;
        this.compressionTaskDispatcher = compressionTaskDispatcher;
        this.memoryConsolidationPlanner = memoryConsolidationPlanner == null ? new MemoryConsolidationPlanner() : memoryConsolidationPlanner;
        this.policy = policy == null ? AssistantRuntimeMaintenancePolicy.DEFAULT : policy;
    }

    public AssistantRuntimeMaintenanceResult beforeQuestion(AssistantScope scope, AssistantRequest request) {
        if (scope == null || !scope.writable()) {
            return AssistantRuntimeMaintenanceResult.skipped();
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
        return new AssistantRuntimeMaintenanceResult(
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
