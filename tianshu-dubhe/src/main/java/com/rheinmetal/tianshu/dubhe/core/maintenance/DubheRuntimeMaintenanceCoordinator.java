package com.rheinmetal.tianshu.dubhe.core.maintenance;

import com.rheinmetal.tianshu.dubhe.DubheRequest;
import com.rheinmetal.tianshu.dubhe.module.memory.maintenance.DubheMemoryMaintenanceService;
import com.rheinmetal.tianshu.dubhe.scope.DubheScope;

public final class DubheRuntimeMaintenanceCoordinator {
    private final DubheMemoryMaintenanceService memoryMaintenanceService;

    public DubheRuntimeMaintenanceCoordinator() {
        this(null);
    }

    public DubheRuntimeMaintenanceCoordinator(DubheMemoryMaintenanceService memoryMaintenanceService) {
        this.memoryMaintenanceService = memoryMaintenanceService;
    }

    public DubheRuntimeMaintenanceResult beforeQuestion(DubheScope scope, DubheRequest request) {
        return requestMaintenance(scope);
    }

    public DubheRuntimeMaintenanceResult afterAssistantAnswer(DubheScope scope) {
        return requestMaintenance(scope);
    }

    /**
     * 立刻跑一次维护，返回是否已受理。
     *
     * <p>重试一个失败的维护用它：判据只有"受理没受理"，因为这一次的结果会走维护自己的成败观察点回来，
     * 不需要在这里当场等一个答案。</p>
     */
    public boolean requestMaintenanceNow(DubheScope scope) {
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

    private DubheRuntimeMaintenanceResult requestMaintenance(DubheScope scope) {
        if (memoryMaintenanceService == null || scope == null || !scope.writable()) {
            return DubheRuntimeMaintenanceResult.skipped();
        }
        return new DubheRuntimeMaintenanceResult(memoryMaintenanceService.requestMaintenance(scope));
    }
}
