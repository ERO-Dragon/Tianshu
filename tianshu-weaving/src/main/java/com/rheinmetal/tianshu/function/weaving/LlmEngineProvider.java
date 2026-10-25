package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.weaving.model.LlmModelPathResolver;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingLoadReport;
import com.rheinmetal.tianshu.function.weaving.service.EmbeddingService;
import com.rheinmetal.tianshu.function.weaving.service.LocalEmbeddingEngine;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.model.NativeModelLoadQueue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Starts and owns the local embedding engine that vector RAG runs on.
 *
 * <p>Cloud is the only chat lane, so this class never builds a chat model. It starts a libs
 * {@code JavaLlamaServer} in embedding-only mode through {@link LocalEmbeddingEngine} and hands the
 * result to the RAG storage, which means a cloud player still gets retrieval without ever owning a
 * chat GGUF.
 *
 * <p>Only cheap decisions happen here: whether a model is selected, whether its file exists, and the
 * CPU/GPU position the player asked for. The model read belongs to the model-load lane, and how it
 * ended — on the card, on the CPU after a fallback, or not at all — arrives through the load
 * listener, which is what lets the caller bind the lane or leave vector RAG off.
 */
public final class LlmEngineProvider {
    /** The module id the native-load gate knows this lane by; also the permit's owner. */
    private static final String MODULE_ID = ProtocolModuleIds.LLM;
    /** Queue action name for the retrieval load, which the gate treats as the primary request. */
    private static final String LOAD_ACTION = "embedding";
    /** Detail reported when the gate has no room left for the primary request. */
    private static final String QUEUE_REFUSED_REASON = "the native model load queue is full";
    private final IGameEnvironment env;
    private final WeavingConfiguration config;
    private final LlmModelPathResolver modelPaths;
    private final InferenceResourcePolicy resourcePolicy;
    private final Executor modelLoadExecutor;
    private final Consumer<EmbeddingLoadReport> loadListener;
    /** Serial gate shared with the speech modules, or null when this provider owns its own loading. */
    private final NativeModelLoadQueue loadQueue;
    private volatile LocalEmbeddingEngine embeddingEngine;
    /** How the bound engine's load ended; null while the model-load lane is still working on it. */
    private volatile EmbeddingLoadReport embeddingLoadReport;

    public LlmEngineProvider(IGameEnvironment env, WeavingConfiguration config) {
        this(env, config, Runnable::run, ignored -> {});
    }

    public LlmEngineProvider(IGameEnvironment env, WeavingConfiguration config, Executor modelLoadExecutor) {
        this(env, config, modelLoadExecutor, ignored -> {});
    }

    public LlmEngineProvider(
            IGameEnvironment env,
            WeavingConfiguration config,
            Executor modelLoadExecutor,
            Consumer<EmbeddingLoadReport> loadListener
    ) {
        this(env, config, modelLoadExecutor, loadListener, null);
    }

    /**
     * @param modelLoadExecutor the lane the model read is queued on; the module passes its model-load
     *                          executor, so starting the LLM module never waits on a GGUF read
     * @param loadListener      told once how the load ended: on the card, on the CPU after a fallback,
     *                          or not at all
     * @param loadQueue         the process-wide serial gate every native model load goes through, or
     *                          null when this provider is used on its own; the embedding load is its
     *                          primary request, because retrieval is a precondition for dialogue
     */
    public LlmEngineProvider(
            IGameEnvironment env,
            WeavingConfiguration config,
            Executor modelLoadExecutor,
            Consumer<EmbeddingLoadReport> loadListener,
            NativeModelLoadQueue loadQueue
    ) {
        this.env = env;
        this.config = config;
        this.modelPaths = new LlmModelPathResolver(config);
        this.resourcePolicy = InferenceResourcePolicy.systemDefault();
        this.modelLoadExecutor = modelLoadExecutor == null ? Runnable::run : modelLoadExecutor;
        this.loadListener = loadListener == null ? ignored -> {} : loadListener;
        this.loadQueue = loadQueue;
    }

    /** Whether an engine is bound and its load is not known to have failed for good. */
    public boolean isEmbeddingConfigured() {
        return embeddingEngine != null && usable(embeddingLoadReport);
    }

    /**
     * Hands the local engine to the model-load lane in embedding-only mode. The cloud lane is the only
     * chat channel, so the chat GGUF is never touched here: cloud users usually have not downloaded
     * one, and a missing chat model must not fail the module.
     *
     * <p>libs 1.0.7 accepts a server configuration that carries only an embedding model, so this goes
     * through the public API: {@link LocalEmbeddingEngine} builds a {@code JavaLlamaServer} with
     * {@code embeddingModel(...)} and no {@code model(...)}, and {@code start()} loads the embedding
     * GGUF on its own.
     *
     * @return true when an engine was created and queued for loading; whether it can embed is reported
     *         later through the load listener, because answering that here is what would put a model
     *         read on the caller's thread
     */
    public boolean startEmbeddingOnly() {
        String embeddingModelName = config.getLlmEmbeddingModelName();
        Path embeddingPath = modelPaths.resolveEmbeddingModel(embeddingModelName);
        // Released before the new engine exists: a libs server holds the whole GGUF and its context as
        // native memory, so two live instances would mean two models resident at once. The release is
        // synchronous, so nothing is ever loaded on top of an engine that is still going away.
        stop();
        // A restart re-enters here, so the claim is re-taken and any primary permit the previous
        // attempt abandoned is cleared in the same step. Re-claiming keeps the speech lane waiting for
        // this load, not for the one that never ran.
        if (loadQueue != null) {
            loadQueue.claimPrimary();
        }
        String unavailableReason = "";
        LocalEmbeddingEngine prepared = null;
        if (embeddingPath == null) {
            unavailableReason = "no embedding model is selected";
        } else if (!Files.isRegularFile(embeddingPath)) {
            unavailableReason = "the embedding model file does not exist: " + embeddingPath;
        } else {
            prepared = createEmbeddingEngine(embeddingPath, embeddingModelName);
        }
        synchronized (this) {
            embeddingEngine = prepared;
            embeddingLoadReport = null;
        }
        if (prepared == null) {
            publishEmbeddingUnavailable(unavailableReason);
            // No retrieval load is coming, so the deferred speech load must not keep waiting for one.
            if (loadQueue != null) {
                loadQueue.settlePrimary();
            }
            return false;
        }
        // Queued, never awaited: the position check and its CPU fallback run on the model-load lane.
        // The permit is taken here rather than inside the lane task so the speech lane parks from world
        // entry on: retrieval is a precondition for dialogue, and a speech model that starts first would
        // hold the permit for the embedding load's whole window.
        if (!warmUpThroughQueue(prepared)) {
            refuseQueuedLoad(prepared);
            return false;
        }
        return true;
    }

    /**
     * The gate refused the primary request: the parked requests already reached its capacity, which means
     * some earlier load never handed its permit back.
     *
     * <p>Ignoring the refusal — which is what this branch used to do — left the primary claim unsettled
     * for the rest of the session: every deferred speech load parked behind a retrieval load that was
     * never going to run, and nothing said so. The engine is therefore unbound, the claim is settled so
     * the speech lane may proceed, and the failure is reported on both the log/diagnostic channel and the
     * presence channel the other load outcomes use.</p>
     */
    private void refuseQueuedLoad(LocalEmbeddingEngine refused) {
        EmbeddingLoadReport report = EmbeddingLoadReport.unavailable(QUEUE_REFUSED_REASON);
        synchronized (this) {
            if (embeddingEngine == refused) {
                embeddingEngine = null;
                embeddingLoadReport = report;
            }
        }
        publishEmbeddingFailure("LLM_EMBEDDING_LOAD_REFUSED", QUEUE_REFUSED_REASON);
        loadListener.accept(report);
        // Nothing is coming for the retrieval lane, so the deferred speech load must not keep waiting.
        if (loadQueue != null) {
            loadQueue.settlePrimary();
        }
    }

    /**
     * Hands the load to the serial gate, which decides when it may run; the permit goes back when the
     * load ends, whether it loaded or not.
     *
     * @return false when the gate refused the request, so the caller can report it instead of pretending
     *         the load was queued
     */
    private boolean warmUpThroughQueue(LocalEmbeddingEngine engine) {
        if (loadQueue == null) {
            engine.warmUp(modelLoadExecutor);
            return true;
        }
        return loadQueue.request(MODULE_ID, LOAD_ACTION, () -> modelLoadExecutor.execute(() -> {
            try {
                engine.loadNow();
            } finally {
                loadQueue.release(MODULE_ID);
            }
        }), NativeModelLoadQueue.Tier.PRIMARY) != NativeModelLoadQueue.Outcome.REFUSED;
    }

    /** Called on stop so a claimed but never-queued primary load cannot park the speech lane. */
    void abandonQueuedLoad() {
        if (loadQueue != null) {
            loadQueue.abandonPrimary();
        }
    }

    private LocalEmbeddingEngine createEmbeddingEngine(Path modelPath, String modelName) {
        return new LocalEmbeddingEngine(
                env,
                modelPath,
                embeddingContextSize(modelName),
                embeddingThreads(),
                requestedGpuLayers(),
                blankToNull(modelName),
                this::onLoadSettled
        );
    }

    /**
     * The runtime window: the configured budget, never more than the model declares it can take.
     *
     * <p>The two numbers answer different questions. The catalog knows what the file was trained for,
     * which is a ceiling; the configuration is what this build is willing to pay native memory for.
     * Using the catalog value directly — as an earlier version did — meant an 8K window for inputs of
     * a few hundred tokens.
     */
    private int embeddingContextSize(String modelName) {
        int ceiling = modelPaths.embeddingContextSize(modelName);
        int budget = config.getLlmEmbeddingContextSize();
        return Math.max(WeavingConfiguration.MIN_EMBEDDING_CONTEXT_SIZE, Math.min(ceiling, budget));
    }

    /** The player's choice, clamped to the only two positions the runtime knows about. */
    private int requestedGpuLayers() {
        int configured = config.getLlmEmbeddingGpuLayers();
        return Math.max(WeavingConfiguration.EMBEDDING_GPU_LAYERS_CPU,
                Math.min(WeavingConfiguration.EMBEDDING_GPU_LAYERS_ALL, configured));
    }

    /**
     * The engine settled. A report from an engine this provider no longer owns is dropped: a restart or
     * a stop already replaced it, so its position no longer describes the lane.
     */
    private void onLoadSettled(LocalEmbeddingEngine engine, EmbeddingLoadReport report) {
        synchronized (this) {
            if (engine != embeddingEngine) {
                return;
            }
            embeddingLoadReport = report;
        }
        loadListener.accept(report);
    }

    /**
     * The engine to bind to the RAG storage, or null when none is bound or the last load failed for
     * good — binding an engine that knows it cannot embed would only turn every vector write into a
     * failure against a lane that is really off.
     */
    public EmbeddingService embeddingService() {
        return isEmbeddingConfigured() ? embeddingEngine : null;
    }

    private int embeddingThreads() {
        return Math.max(1, Math.min(resourcePolicy.processors(), resourcePolicy.embeddingGpuHelperThreads()));
    }

    private void publishEmbeddingUnavailable(String reason) {
        publishEmbeddingFailure("LLM_EMBEDDING_UNAVAILABLE", reason);
    }

    /** The one log-plus-diagnostic outlet for "this lane will not carry vectors", whatever the cause. */
    private void publishEmbeddingFailure(String code, String reason) {
        String detail = reason == null || reason.isBlank() ? "no embedding model is configured" : reason;
        String embeddingModelName = config.getLlmEmbeddingModelName();
        env.warn("[LLM] Vector RAG stays disabled: " + detail + ". Chat is unaffected.");
        try {
            env.diagnostics().publish(DiagnosticEvent.now(
                    ProtocolModuleIds.LLM,
                    code,
                    DiagnosticSeverity.WARN,
                    DiagnosticPrivacy.REDACTED,
                    Map.of(
                            "reason", detail,
                            "embeddingModel", embeddingModelName == null ? "" : embeddingModelName,
                            "embeddingConfigured", "false"
                    )
            ));
        } catch (RuntimeException diagnosticFailure) {
            env.warn("Failed to publish LLM embedding diagnostics: " + diagnosticFailure.getMessage());
        }
    }

    private static boolean usable(EmbeddingLoadReport report) {
        return report == null || report.usable();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Releases the embedding engine; the next {@link #startEmbeddingOnly()} builds a fresh one. */
    public void stop() {
        LocalEmbeddingEngine embedding;
        synchronized (this) {
            embedding = embeddingEngine;
            embeddingEngine = null;
            embeddingLoadReport = null;
        }
        // Not abandoned here: startEmbeddingOnly() calls stop() before it arms the gate, and resetting
        // there would drop the primary claim it is about to take. Session teardown calls
        // abandonQueuedLoad() instead.
        if (embedding != null) {
            embedding.shutdown();
        }
    }
}
