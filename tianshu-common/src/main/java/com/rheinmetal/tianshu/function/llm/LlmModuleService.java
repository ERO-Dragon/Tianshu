package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.server.LlmServerProcessManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmModuleService {
    private final ITianshuConfig config;
    private final AtomicReference<LlmServerProcessManager> processManager = new AtomicReference<>();
    private final AtomicReference<LlmRuntimeState> state = new AtomicReference<>(LlmRuntimeState.STOPPED);
    private final AtomicReference<String> failureMessage = new AtomicReference<>("");

    public LlmModuleService(ITianshuConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public void bindProcessManager(LlmServerProcessManager manager) {
        processManager.set(manager);
        state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
        failureMessage.set("");
    }

    public void unbindProcessManager(LlmServerProcessManager manager) {
        processManager.compareAndSet(manager, null);
        state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
    }

    public LlmControlResult load() {
        if (!config.isLlmEnabled()) {
            unload();
            state.set(LlmRuntimeState.DISABLED);
            return LlmControlResult.rejected("LLM is disabled");
        }
        LlmServerProcessManager manager = processManager.get();
        if (manager == null) {
            state.set(LlmRuntimeState.FAILED);
            failureMessage.set("LLM runtime is not prepared");
            return LlmControlResult.rejected(failureMessage.get());
        }
        if (manager.isLlmRunning()) {
            state.set(manager.isLlmHealthy() ? LlmRuntimeState.RUNNING : LlmRuntimeState.STARTING);
            return LlmControlResult.accepted(state.get(), "LLM is already running");
        }
        failureMessage.set("");
        state.set(LlmRuntimeState.STARTING);
        manager.startLlmServer();
        return LlmControlResult.accepted(LlmRuntimeState.STARTING, "LLM loading started");
    }

    public LlmControlResult unload() {
        LlmServerProcessManager manager = processManager.get();
        if (manager == null) {
            state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
            return LlmControlResult.accepted(state.get(), "LLM runtime is not prepared");
        }
        if (!manager.isLlmRunning()) {
            state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
            return LlmControlResult.accepted(state.get(), "LLM is not running");
        }
        state.set(LlmRuntimeState.STOPPING);
        manager.stopLlmServer();
        state.set(config.isLlmEnabled() ? LlmRuntimeState.STOPPED : LlmRuntimeState.DISABLED);
        return LlmControlResult.accepted(state.get(), "LLM unloaded");
    }

    public void markReady() {
        failureMessage.set("");
        state.set(config.isLlmEnabled() ? LlmRuntimeState.RUNNING : LlmRuntimeState.DISABLED);
    }

    public void markFailed(String reason) {
        failureMessage.set(reason == null ? "" : reason.trim());
        state.set(config.isLlmEnabled() ? LlmRuntimeState.FAILED : LlmRuntimeState.DISABLED);
    }

    public LlmRuntimeSnapshot snapshot() {
        boolean enabled = config.isLlmEnabled();
        LlmServerProcessManager manager = processManager.get();
        boolean running = enabled && manager != null && manager.isLlmRunning();
        boolean healthy = enabled && manager != null && manager.isLlmHealthy();
        LlmRuntimeState current = enabled ? state.get() : LlmRuntimeState.DISABLED;
        if (enabled && running && healthy) current = LlmRuntimeState.RUNNING;
        if (enabled && !running && current == LlmRuntimeState.RUNNING) current = LlmRuntimeState.STOPPED;
        return new LlmRuntimeSnapshot(enabled, current, running, healthy, config.getCustomLlmName(), failureMessage.get(), System.currentTimeMillis());
    }
}
