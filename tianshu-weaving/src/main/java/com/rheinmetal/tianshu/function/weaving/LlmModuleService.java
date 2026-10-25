package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.function.weaving.account.LlmAccountKey;
import com.rheinmetal.tianshu.function.weaving.account.LlmAccountProbe;
import com.rheinmetal.tianshu.function.weaving.account.LlmAccountQuery;
import com.rheinmetal.tianshu.function.weaving.account.LlmAccountSnapshot;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogClient;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogKeySource;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogResult;
import com.rheinmetal.tianshu.function.weaving.catalog.LlmModelCatalogState;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmControlResult;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeSnapshot;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPricing;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageSnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LlmModuleService {
    public interface RuntimeController {
        void start();
        void stop();
    }

    private final WeavingConfiguration config;
    private final LlmUsageLedger usageLedger;
    private final LlmAccountQuery accountQuery = new LlmAccountQuery();
    private final AtomicReference<LlmModelCatalogClient> modelCatalogClient =
            new AtomicReference<>(LlmModelCatalogClient.NOT_WIRED);
    private final AtomicReference<LlmRuntimeState> state = new AtomicReference<>(LlmRuntimeState.STOPPED);
    private final AtomicReference<String> failureMessage = new AtomicReference<>("");
    private final AtomicReference<RuntimeController> runtimeController = new AtomicReference<>();

    /**
     * @param usageLedger the ledger the request path records into; this service only reads it
     */
    public LlmModuleService(WeavingConfiguration config, LlmUsageLedger usageLedger) {
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

    /**
     * Identity of the account the configured endpoint is billed to. The credential is reduced to a
     * fingerprint here, so neither the key nor a value derived from it can be read back out.
     */
    public LlmAccountKey accountKey() {
        return LlmAccountKey.of(config.getLlmRemoteBaseUrl(), config.getLlmRemoteApiKey());
    }

    /**
     * Binds the provider-side account probe that answers balance and cost queries. Until one is
     * bound every read reports "not wired", which the HUD renders as a placeholder.
     */
    public void bindAccountProbe(LlmAccountProbe probe) {
        accountQuery.bindProbe(probe);
    }

    /**
     * Newest provider answer for the configured account. The amounts are authoritative provider
     * figures; local price conversion never flows into this snapshot.
     */
    public LlmAccountSnapshot accountSnapshot() {
        return accountQuery.snapshot(accountKey());
    }

    /**
     * Requests fresh provider figures. Non-blocking and rate limited, so it is safe to call from a
     * UI refresh tick: the probe owns the threading and a failure can only change the snapshot's
     * state, never the rendered amounts of another row.
     */
    public void refreshAccount() {
        accountQuery.refresh(accountKey());
    }

    /**
     * Same, and runs {@code onAnswer} once the figures are stored.
     *
     * <p>That is what lets a page repaint when the answer arrives instead of polling the snapshot: the
     * callback runs on the probe's thread, so a display marshals it to its own.
     */
    public void refreshAccount(Runnable onAnswer) {
        accountQuery.refresh(accountKey(), onAnswer);
    }

    /**
     * Binds the source of provider model lists. Until one is bound, asking for a catalog reports
     * "not wired" instead of an empty list, so the page cannot mistake a missing client for a provider
     * that serves no model.
     */
    public void bindModelCatalogClient(LlmModelCatalogClient client) {
        modelCatalogClient.set(client == null ? LlmModelCatalogClient.NOT_WIRED : client);
    }

    /**
     * Asks the given endpoint which models it serves.
     *
     * <p>The endpoint and the credential are passed per call, not read from configuration, because the
     * settings page asks about the provider the player is <em>editing</em> — which may not be saved yet.
     * The credential may therefore be blank: the request is still sent, and the provider's answer is
     * what tells "no key was sent" from "the key was refused".
     *
     * <p>Non-blocking: the bound client owns the threading, and the sink is invoked exactly once, from
     * a worker thread. A failure is a state with its language key, never a silent empty answer.
     *
     * @param keySource where the credential came from; diagnostic metadata, never a credential
     * @param sink      answer sink; may be {@code null}
     */
    public void requestModelCatalog(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource,
                                    Consumer<LlmModelCatalogResult> sink) {
        Consumer<LlmModelCatalogResult> target = sink == null ? result -> { } : sink;
        LlmModelCatalogClient client = modelCatalogClient.get();
        if (client == null || !client.wired()) {
            target.accept(LlmModelCatalogResult.notWired());
            return;
        }
        // One answer per call, even if a misbehaving client reports twice or throws after reporting.
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<LlmModelCatalogResult> once = result -> {
            if (delivered.compareAndSet(false, true)) {
                target.accept(result == null ? LlmModelCatalogResult.notWired() : result);
            }
        };
        try {
            client.listModels(baseUrl, apiKey,
                    keySource == null ? LlmModelCatalogKeySource.NONE : keySource, once);
        } catch (RuntimeException brokenClient) {
            once.accept(LlmModelCatalogResult.failure(
                    LlmModelCatalogState.NETWORK_ERROR,
                    brokenClient.getClass().getSimpleName()));
        }
    }
}
