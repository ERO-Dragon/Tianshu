package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmModuleService implements LlmPerformanceProvider {
    public interface RuntimeController {
        void start();
        void stop();
    }

    private final LlmConfiguration config;
    private final AtomicReference<LlmRuntimeState> state = new AtomicReference<>(LlmRuntimeState.STOPPED);
    private final AtomicReference<String> failureMessage = new AtomicReference<>("");
    private final AtomicReference<RuntimeController> runtimeController = new AtomicReference<>();
    private final AtomicReference<LlmPerformanceProvider> performanceProvider = new AtomicReference<>(LlmPerformanceProvider.UNAVAILABLE);

    public LlmModuleService(LlmConfiguration config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void bindRuntimeController(RuntimeController controller) {
        runtimeController.set(controller);
    }

    public void bindPerformanceProvider(LlmPerformanceProvider provider) {
        performanceProvider.set(provider == null ? LlmPerformanceProvider.UNAVAILABLE : provider);
    }

    @Override
    public LlmPerformanceSnapshot performanceSnapshot() {
        return performanceProvider.get().performanceSnapshot();
    }

    public LlmControlResult load() {
        if (!config.isLlmEnabled()) {
            state.set(LlmRuntimeState.DISABLED);
            return LlmControlResult.rejected("LLM is disabled");
        }
        LlmRuntimeState current = state.get();
        if (current == LlmRuntimeState.RUNNING) {
            return LlmControlResult.accepted(current, "LLM is already running");
        }
        if (current == LlmRuntimeState.STARTING) {
            return LlmControlResult.accepted(current, "LLM is loading");
        }
        failureMessage.set("");
        state.set(LlmRuntimeState.STARTING);
        RuntimeController controller = runtimeController.get();
        if (controller == null) {
            markFailed("LLM runtime controller is not bound");
            return LlmControlResult.rejected("LLM runtime controller is not bound");
        }
        try {
            controller.start();
        } catch (Exception e) {
            markFailed(e.getMessage());
            return LlmControlResult.rejected(e.getMessage());
        }
        return LlmControlResult.accepted(LlmRuntimeState.STARTING, "LLM loading started");
    }

    public LlmControlResult unload() {
        LlmRuntimeState current = state.get();
        if (current == LlmRuntimeState.STOPPED || current == LlmRuntimeState.DISABLED) {
            return LlmControlResult.accepted(current, "LLM is not running");
        }
        if (current == LlmRuntimeState.FAILED) {
            RuntimeController controller = runtimeController.get();
            if (controller != null) {
                try {
                    controller.stop();
                } catch (Exception e) {
                    markFailed(e.getMessage());
                    return LlmControlResult.rejected(e.getMessage());
                }
            } else {
                markStopped();
            }
            return LlmControlResult.accepted(state.get(), "LLM runtime was failed");
        }
        state.set(LlmRuntimeState.STOPPING);
        RuntimeController controller = runtimeController.get();
        if (controller != null) {
            try {
                controller.stop();
            } catch (Exception e) {
                markFailed(e.getMessage());
                return LlmControlResult.rejected(e.getMessage());
            }
        } else {
            markStopped();
        }
        return LlmControlResult.accepted(state.get(), "LLM unload requested");
    }

    public void markReady() {
        failureMessage.set("");
        state.set(config.isLlmEnabled() ? LlmRuntimeState.RUNNING : LlmRuntimeState.DISABLED);
    }

    public void markFailed(String reason) {
        failureMessage.set(reason == null ? "" : reason.trim());
        state.set(config.isLlmEnabled() ? LlmRuntimeState.FAILED : LlmRuntimeState.DISABLED);
    }

    public void markStopped() {
        failureMessage.set("");
        state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
    }

    public LlmRuntimeSnapshot snapshot() {
        boolean enabled = config.isLlmEnabled();
        LlmRuntimeState current = enabled ? state.get() : LlmRuntimeState.DISABLED;
        boolean running = enabled && current == LlmRuntimeState.RUNNING;
        boolean healthy = enabled && current == LlmRuntimeState.RUNNING;
        return new LlmRuntimeSnapshot(enabled, current, running, healthy, config.getCustomLlmName(), failureMessage.get(), System.currentTimeMillis());
    }
}
