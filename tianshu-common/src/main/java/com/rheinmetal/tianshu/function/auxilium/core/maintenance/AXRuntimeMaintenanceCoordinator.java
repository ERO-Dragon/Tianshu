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
