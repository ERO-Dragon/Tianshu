package com.rheinmetal.tianshu.core;

import com.rheinmetal.tianshu.Tianshu;

public class EnvSetupManager {

    public interface SetupCallback {
        void onProgress(String stage, int percent);
        void onSuccess();
        void onError(String message);
    }

    private static volatile boolean setupCompleted = false;

    private EnvSetupManager() {}

    public static boolean isSetupCompleted() {
        return setupCompleted;
    }

    public static void markSetupCompleted() {
        setupCompleted = true;
    }

    public static boolean isEnvironmentReady() {
        return NativeLibManager.checkNativesReady();
    }

    public static void startSetup(SetupCallback callback) {
        Thread thread = new Thread(() -> {
            try {
                callback.onProgress("正在检测 Native 库...", 30);

                if (!NativeLibManager.isNativesExtracted()) {
                    callback.onProgress("正在提取 DLL...", 50);
                    NativeLibManager.extractNatives();
                }

                if (!NativeLibManager.isNativesLoaded()) {
                    callback.onProgress("正在加载 DLL...", 70);
                    NativeLibManager.loadNatives();
                }

                if (NativeLibManager.checkNativesReady()) {
                    NativeLibManager.extractServerJar();
                    callback.onProgress("环境已就绪", 100);
                    setupCompleted = true;
                    callback.onSuccess();
                } else {
                    callback.onError("Native 库检测失败，请检查模组完整性");
                }
            } catch (Exception e) {
                Tianshu.LOGGER.error("环境检查失败", e);
                callback.onError("环境检查失败: " + e.getMessage());
            }
        }, "Tianshu-EnvSetup");
        thread.setDaemon(true);
        thread.start();
    }
}
