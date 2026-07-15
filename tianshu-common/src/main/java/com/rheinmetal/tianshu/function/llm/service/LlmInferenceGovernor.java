package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceSnapshot;

public final class LlmInferenceGovernor {
    private static final float CHAT_DEFAULT_PRIORITY = 0.72f;
    private static final float TASK_DEFAULT_PRIORITY = 0.62f;
    private static final float CHAT_MIN_PRIORITY = 0.25f;
    private static final float TASK_MIN_PRIORITY = 0.20f;
    private static final float MEDIUM_PRIORITY = 0.48f;
    private static final float LOW_SHARED_PRIORITY = 0.34f;
    private static final float HIGH_PRIORITY = 0.82f;
    private static final double GPU_BUSY = 0.82D;
    private static final double GPU_IDLE = 0.55D;

    private final LlmInferenceDefaults defaults;
    private final LlmPerformanceProvider performanceProvider;

    public LlmInferenceGovernor(LlmConfiguration config, LlmPerformanceProvider performanceProvider) {
        this(LlmInferenceDefaults.from(config), performanceProvider);
    }

    LlmInferenceGovernor(LlmInferenceDefaults defaults, LlmPerformanceProvider performanceProvider) {
        this.defaults = defaults == null ? LlmInferenceDefaults.safe() : defaults;
        this.performanceProvider = performanceProvider == null ? LlmPerformanceProvider.UNAVAILABLE : performanceProvider;
    }

    public LlmInferenceOptions resolve(LlmInferencePolicy override, boolean taskLane, boolean mtpSupported) {
        LlmInferencePolicy effective = effectivePolicy(override);
        boolean mtpEnabled = Boolean.TRUE.equals(effective.mtpEnabled()) && mtpSupported;
        Float vulkanPriority = resolveVulkanPriority(effective, taskLane);
        return new LlmInferenceOptions(mtpEnabled, null, vulkanPriority, false, null);
    }

    private LlmInferencePolicy effectivePolicy(LlmInferencePolicy override) {
        boolean frameGuard = override != null && override.frameGuardEnabled() != null
                ? override.frameGuardEnabled()
                : defaults.frameGuardEnabled();
        int targetFps = override != null && override.targetFps() != null
                ? override.targetFps()
                : defaults.targetFps();
        boolean mtp = override != null && override.mtpEnabled() != null
                ? override.mtpEnabled()
                : defaults.mtpEnabled();
        return new LlmInferencePolicy(frameGuard, targetFps, mtp);
    }

    private Float resolveVulkanPriority(LlmInferencePolicy policy, boolean taskLane) {
        if (!Boolean.TRUE.equals(policy.frameGuardEnabled())) {
            return null;
        }

        LlmPerformanceSnapshot snapshot = performanceProvider.performanceSnapshot();
        if (snapshot == null || !snapshot.available() || !snapshot.llmUsesGpu()) {
            return null;
        }

        if (!snapshot.llmSharesRenderGpu()) {
            return null;
        }

        if (!snapshot.gpuUtilizationAvailable()) {
            return fallbackFpsOnlyPriority(snapshot, policy.targetFps(), taskLane);
        }

        int targetFps = policy.targetFps() == null ? defaults.targetFps() : policy.targetFps();
        boolean fpsLow = snapshot.fps() > 0 && snapshot.fps() < Math.round(targetFps * 0.92D);
        boolean fpsHealthy = snapshot.fps() >= targetFps;
        boolean gpuBusy = snapshot.gpuUtilization() >= GPU_BUSY;
        boolean gpuIdle = snapshot.gpuUtilization() <= GPU_IDLE;

        if (gpuBusy && fpsLow) {
            return laneMinPriority(taskLane);
        }
        if (gpuBusy) {
            return LOW_SHARED_PRIORITY;
        }
        if (gpuIdle && fpsHealthy) {
            return HIGH_PRIORITY;
        }
        return MEDIUM_PRIORITY;
    }

    private Float fallbackFpsOnlyPriority(LlmPerformanceSnapshot snapshot, Integer targetFps, boolean taskLane) {
        int effectiveTarget = targetFps == null ? defaults.targetFps() : targetFps;
        if (snapshot.fps() > 0 && snapshot.fps() < Math.round(effectiveTarget * 0.85D)) {
            return taskLane ? 0.32f : 0.38f;
        }
        return laneDefaultPriority(taskLane);
    }

    private static float laneDefaultPriority(boolean taskLane) {
        return taskLane ? TASK_DEFAULT_PRIORITY : CHAT_DEFAULT_PRIORITY;
    }

    private static float laneMinPriority(boolean taskLane) {
        return taskLane ? TASK_MIN_PRIORITY : CHAT_MIN_PRIORITY;
    }
}
