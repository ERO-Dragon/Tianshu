package com.rheinmetal.tianshu.client.gui.llm;

import com.rheinmetal.tianshu.config.ClientConfig;
import com.rheinmetal.tianshu.core.TianshuCoreManager;
import com.rheinmetal.tianshu.function.llm.LlmModuleService;

public final class ClientLlmRuntimeBridge {
    private static NeoForgeLlmPerformanceProvider performanceProvider;

    private ClientLlmRuntimeBridge() {
    }

    public static void bind(TianshuCoreManager coreManager, ClientConfig config) {
        if (coreManager == null || config == null) {
            return;
        }
        if (performanceProvider == null) {
            performanceProvider = new NeoForgeLlmPerformanceProvider(config);
        }
        coreManager.findService(LlmModuleService.class)
                .ifPresent(service -> service.bindPerformanceProvider(performanceProvider));
    }

    public static void markFrame() {
        if (performanceProvider != null) {
            performanceProvider.markFrame();
        }
    }
}
