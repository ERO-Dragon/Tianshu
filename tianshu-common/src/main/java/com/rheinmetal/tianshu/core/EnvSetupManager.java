package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.INativeLibBridge;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

public class EnvSetupManager {

    public interface SetupCallback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onError(String message);
    }

    private final IGameEnvironment env;
    private final INativeLibBridge nativeLibBridge;
    private final ProtocolExecutorManager executorManager;
    private volatile boolean setupCompleted = false;

    public EnvSetupManager(IGameEnvironment env, INativeLibBridge nativeLibBridge, ProtocolExecutorManager executorManager) {
        this.env = env;
        this.nativeLibBridge = nativeLibBridge;
        this.executorManager = executorManager;
    }

    public boolean isSetupCompleted() {
        return setupCompleted;
    }

    public void markSetupCompleted() {
        setupCompleted = true;
    }

    public boolean isEnvironmentReady() {
        return nativeLibBridge.isNativesReady();
    }

    public void startSetup(SetupCallback callback) {
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("core.env.setup")
                        .lane(ExecutionLane.MODEL_LOAD)
                        .concurrencyKey("core.env.setup")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> {
            try {
                callback.onProgress("正在检测 Native 库...", 30);

                if (!nativeLibBridge.isNativesReady()) {
                    callback.onProgress("正在提取并加载 DLL...", 60);
                    nativeLibBridge.extractAndLoadAll();
                }

                if (nativeLibBridge.isNativesReady()) {
                    nativeLibBridge.extractServerJar();
                    callback.onProgress("环境已就绪", 100);
                    setupCompleted = true;
                    callback.onSuccess();
                } else {
                    callback.onError("Native 库检测失败，请检查模组完整性");
                }
            } catch (Exception e) {
                env.error("环境检查失败", e);
                callback.onError("环境检查失败: " + e.getMessage());
            }
        });
    }
}
