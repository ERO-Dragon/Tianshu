package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.ITianshuConfig;

record LlmInferenceDefaults(boolean frameGuardEnabled, int targetFps, boolean mtpEnabled) {
    private static final int SAFE_TARGET_FPS = 60;

    static LlmInferenceDefaults from(ITianshuConfig config) {
        return config == null
                ? safe()
                : new LlmInferenceDefaults(
                        config.isLlmFrameGuardEnabled(),
                        config.getLlmFrameGuardTargetFps(),
                        config.isLlmMtpEnabled()
                );
    }

    static LlmInferenceDefaults safe() {
        return new LlmInferenceDefaults(false, SAFE_TARGET_FPS, false);
    }

    LlmInferenceDefaults {
        targetFps = Math.max(15, Math.min(240, targetFps));
    }
}
