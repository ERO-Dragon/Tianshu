package com.rheinmetal.tianshu.function.auxilium.core.maintenance;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.module.memory.maintenance.AXMemoryMaintenanceService;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;

public final class AXRuntimeMaintenanceCoordinator {
    private final AXMemoryMaintenanceService memoryMaintenanceService;

    public AXRuntimeMaintenanceCoordinator() {
        this(null);
    }

    public AXRuntimeMaintenanceCoordinator(AXMemoryMaintenanceService memoryMaintenanceService) {
        this.memoryMaintenanceService = memoryMaintenanceService;
    }

    public AXRuntimeMaintenanceResult beforeQuestion(AXScope scope, AXRequest request) {
        return requestMaintenance(scope);
    }

    public AXRuntimeMaintenanceResult afterAssistantAnswer(AXScope scope) {
        return requestMaintenance(scope);
    }

    /**
     * 立刻跑一次维护，返回是否已受理。
     *
     * <p>重试一个失败的维护用它：判据只有"受理没受理"，因为这一次的结果会走维护自己的成败观察点回来，
     * 不需要在这里当场等一个答案。</p>
     */
    public boolean requestMaintenanceNow(AXScope scope) {
        return memoryMaintenanceService != null
                && scope != null
                && scope.writable()
                && memoryMaintenanceService.requestMaintenance(scope);
    }

    public void stop() {
        if (memoryMaintenanceService != null) {
            memoryMaintenanceService.stop();
        }
    }

    private AXRuntimeMaintenanceResult requestMaintenance(AXScope scope) {
        if (memoryMaintenanceService == null || scope == null || !scope.writable()) {
            return AXRuntimeMaintenanceResult.skipped();
        }
        return new AXRuntimeMaintenanceResult(memoryMaintenanceService.requestMaintenance(scope));
    }
}
