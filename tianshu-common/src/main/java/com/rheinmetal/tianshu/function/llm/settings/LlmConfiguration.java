package com.rheinmetal.tianshu.function.llm.settings;

import java.nio.file.Path;

public interface LlmConfiguration {
    boolean isLlmEnabled();

    String getCustomLlmName();

    Path getLlmBasePath();

    default Path getLlmMtpDraftGgufFilePath() {
        return null;
    }

    default String getLlmEmbeddingModelName() {
        return "";
    }

    default String getLlmGpuDeviceId() {
        return "";
    }

    default boolean isLlmFrameGuardEnabled() {
        return true;
    }

    default int getLlmFrameGuardTargetFps() {
        return 60;
    }

    default boolean isLlmMtpEnabled() {
        return false;
    }

    default int getLlmRequestTimeoutSeconds() {
        return 120;
    }

    default int getLlmTaskAdmissionQueueSize() {
        return 2;
    }

    default int getLlmTaskAgingBoostPerRequest() {
        return 1;
    }

    default int getLlmLibsChatQueueSize() {
        return 1;
    }

    default String getLlmCacheTypeK() {
        return "q8_0";
    }

    default String getLlmCacheTypeV() {
        return "q8_0";
    }

    default Path getLlmRagRootPath() {
        return getLlmBasePath().resolve("rag").resolve("root");
    }

    default Path getLlmRagCacheRootPath() {
        return getLlmBasePath().resolve("ragCache");
    }
}
