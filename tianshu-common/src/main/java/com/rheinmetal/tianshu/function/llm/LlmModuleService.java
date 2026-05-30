package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmModuleService {
    private final ITianshuConfig config;
    private final AtomicReference<LlmRuntimeState> state = new AtomicReference<>(LlmRuntimeState.STOPPED);
    private final AtomicReference<String> failureMessage = new AtomicReference<>("");
    private final AtomicReference<Runnable> onReadyCallback = new AtomicReference<>();
    private final AtomicReference<Runnable> onFailedCallback = new AtomicReference<>();

    public LlmModuleService(ITianshuConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void setReadyCallback(Runnable onReady) {
        onReadyCallback.set(onReady);
    }

    public void setFailedCallback(Runnable onFailed) {
        onFailedCallback.set(onFailed);
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
        return LlmControlResult.accepted(LlmRuntimeState.STARTING, "LLM loading started");
    }

    public LlmControlResult unload() {
        LlmRuntimeState current = state.get();
        if (current == LlmRuntimeState.STOPPED || current == LlmRuntimeState.DISABLED) {
            return LlmControlResult.accepted(current, "LLM is not running");
        }
        if (current == LlmRuntimeState.FAILED) {
            state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
            return LlmControlResult.accepted(state.get(), "LLM runtime was failed");
        }
        state.set(LlmRuntimeState.STOPPING);
        state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
        return LlmControlResult.accepted(state.get(), "LLM unloaded");
    }

    public void markReady() {
        failureMessage.set("");
        state.set(config.isLlmEnabled() ? LlmRuntimeState.RUNNING : LlmRuntimeState.DISABLED);
        Runnable callback = onReadyCallback.getAndSet(null);
        if (callback != null) {
            callback.run();
        }
    }

    public void markFailed(String reason) {
        failureMessage.set(reason == null ? "" : reason.trim());
        state.set(config.isLlmEnabled() ? LlmRuntimeState.FAILED : LlmRuntimeState.DISABLED);
        Runnable callback = onFailedCallback.getAndSet(null);
        if (callback != null) {
            callback.run();
        }
    }

    public LlmRuntimeSnapshot snapshot() {
        boolean enabled = config.isLlmEnabled();
        LlmRuntimeState current = enabled ? state.get() : LlmRuntimeState.DISABLED;
        boolean running = enabled && current == LlmRuntimeState.RUNNING;
        boolean healthy = enabled && current == LlmRuntimeState.RUNNING;
        return new LlmRuntimeSnapshot(enabled, current, running, healthy, config.getCustomLlmName(), failureMessage.get(), System.currentTimeMillis());
    }
}