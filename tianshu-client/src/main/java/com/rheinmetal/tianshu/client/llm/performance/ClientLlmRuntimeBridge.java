package com.rheinmetal.tianshu.client.llm.performance;

import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.llm.LlmModuleService;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

public final class ClientLlmRuntimeBridge {
    private static ClientLlmPerformanceProvider performanceProvider;

    private ClientLlmRuntimeBridge() {
    }

    public static void bind(TianshuCoreManager coreManager, LlmConfiguration config) {
        if (coreManager == null || config == null) {
            return;
        }
        if (performanceProvider == null) {
            performanceProvider = new ClientLlmPerformanceProvider(config);
        }
        coreManager.findService(LlmModuleService.class)
                .ifPresent(service -> service.bindPerformanceProvider(performanceProvider));
    }

    public static void markFrame() {
        if (performanceProvider != null) {
            performanceProvider.markFrame();
        }
    }

    public static void close() {
        performanceProvider = null;
        GpuInfo.close();
    }
}
