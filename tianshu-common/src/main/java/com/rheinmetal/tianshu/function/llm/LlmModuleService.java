package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.llm.usage.LlmPricing;
import com.rheinmetal.tianshu.function.llm.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.function.llm.usage.LlmUsageSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class LlmModuleService {
    public interface RuntimeController {
        void start();
        void stop();
    }

    private final LlmConfiguration config;
    private final LlmUsageLedger usageLedger;
    private final AtomicReference<LlmRuntimeState> state = new AtomicReference<>(LlmRuntimeState.STOPPED);
    private final AtomicReference<String> failureMessage = new AtomicReference<>("");
    private final AtomicReference<RuntimeController> runtimeController = new AtomicReference<>();

    /**
     * @param usageLedger the ledger the request path records into; this service only reads it
     */
    public LlmModuleService(LlmConfiguration config, LlmUsageLedger usageLedger) {
        this.config = Objects.requireNonNull(config, "config");
        this.usageLedger = usageLedger == null ? new LlmUsageLedger() : usageLedger;
    }

    public void bindRuntimeController(RuntimeController controller) {
        runtimeController.set(controller);
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

    /**
     * Read-only token accounting for every request the module has served, per attribution key and in
     * total. The view is an immutable copy, so a reader can hold it without locking the recorder.
     */
    public LlmUsageSnapshot usageSnapshot() {
        return usageLedger.snapshot();
    }

    /**
     * Prices the ledger is read against, taken from configuration. Amounts must be hidden rather
     * than shown as zero when {@link LlmPricing#configured()} is false.
     */
    public LlmPricing usagePricing() {
        return new LlmPricing(
                LlmPricing.microsFromPrice(config.getLlmUsageCacheMissInputPricePerMillion()),
                LlmPricing.microsFromPrice(config.getLlmUsageCacheHitInputPricePerMillion()),
                LlmPricing.microsFromPrice(config.getLlmUsageOutputPricePerMillion())
        );
    }
}
