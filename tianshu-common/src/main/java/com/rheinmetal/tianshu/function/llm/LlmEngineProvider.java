package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.function.llm.model.LlmModelPathResolver;
import com.rheinmetal.tianshu.function.llm.service.EmbeddingService;
import com.rheinmetal.tianshu.function.llm.service.LocalEmbeddingEngine;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Starts and owns the local embedding engine that vector RAG runs on.
 *
 * <p>Cloud is the only chat lane, so this class never builds a chat model. It starts a libs
 * {@code JavaLlamaServer} in embedding-only mode through {@link LocalEmbeddingEngine} and hands the
 * result to the RAG storage, which means a cloud player still gets retrieval without ever owning a
 * chat GGUF.
 */
public final class LlmEngineProvider {
    /**
     * Embedding runs on the CPU on purpose. A query embedding is one short burst per turn and memory
     * indexing happens in the background, so the lane does not need the card and the GPU budget stays
     * with rendering and with whatever the player runs themselves. Opening a Vulkan device for the
     * embedding model while no layer is offloaded made inline RAG search fail with
     * "vk::Device::createComputePipeline: ErrorUnknown", so this is also the only configuration the
     * current backend accepts.
     */
    private static final int EMBEDDING_GPU_LAYERS = 0;

    private final IGameEnvironment env;
    private final LlmConfiguration config;
    private final LlmModelPathResolver modelPaths;
    private final InferenceResourcePolicy resourcePolicy;
    private final Executor modelLoadExecutor;
    private LocalEmbeddingEngine embeddingEngine;
    private volatile boolean embeddingConfigured;

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config) {
        this(env, config, Runnable::run);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, Executor modelLoadExecutor) {
        this.env = env;
        this.config = config;
        this.modelPaths = new LlmModelPathResolver(config);
        this.resourcePolicy = InferenceResourcePolicy.systemDefault();
        this.modelLoadExecutor = modelLoadExecutor == null ? Runnable::run : modelLoadExecutor;
    }

    /** Whether the last {@link #startEmbeddingOnly()} call bound an embedding model. */
    public boolean isEmbeddingConfigured() {
        return embeddingConfigured;
    }

    /**
     * Starts the local engine in embedding-only mode and reports whether an embedding model is now
     * bound. The cloud lane is the only chat channel, so the chat GGUF is never touched here: cloud
     * users usually have not downloaded one, and a missing chat model must not fail the module.
     *
     * <p>libs 1.0.7 accepts a server configuration that carries only an embedding model, so this goes
     * through the public API: {@link LocalEmbeddingEngine} builds a {@code JavaLlamaServer} with
     * {@code embeddingModel(...)} and no {@code model(...)}, and {@code start()} loads the embedding
     * GGUF on its own. The load itself runs on the model-load executor and therefore does not block
     * the runtime start.
     *
     * @return true when the embedding engine was created and may be bound to the RAG storage
     */
    public boolean startEmbeddingOnly() {
        Path embeddingPath = modelPaths.resolveEmbeddingModel();
        String unavailableReason = "";
        LocalEmbeddingEngine prepared = null;
        if (embeddingPath == null) {
            unavailableReason = "no embedding model is selected";
        } else if (!Files.isRegularFile(embeddingPath)) {
            unavailableReason = "the embedding model file does not exist: " + embeddingPath;
        } else {
            prepared = new LocalEmbeddingEngine(
                    env,
                    embeddingPath,
                    modelPaths.embeddingContextSize(),
                    embeddingThreads(),
                    EMBEDDING_GPU_LAYERS,
                    blankToNull(config.getLlmEmbeddingModelName())
            );
        }
        LocalEmbeddingEngine replaced;
        synchronized (this) {
            replaced = embeddingEngine;
            embeddingEngine = prepared;
            embeddingConfigured = prepared != null;
        }
        if (replaced != null && replaced != prepared) {
            replaced.shutdown();
        }
        if (prepared == null) {
            publishEmbeddingUnavailable(unavailableReason);
            return false;
        }
        prepared.warmUp(modelLoadExecutor);
        return true;
    }

    /** The embedding engine created by {@link #startEmbeddingOnly()}, or null when none is configured. */
    public EmbeddingService embeddingService() {
        return embeddingEngine;
    }

    private int embeddingThreads() {
        return Math.max(1, Math.min(resourcePolicy.processors(), resourcePolicy.llmGpuHelperThreads()));
    }

    private void publishEmbeddingUnavailable(String reason) {
        String detail = reason == null || reason.isBlank() ? "no embedding model is configured" : reason;
        String embeddingModelName = config.getLlmEmbeddingModelName();
        env.warn("[LLM] Vector RAG stays disabled: " + detail + ". Chat is unaffected.");
        try {
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.llm",
                    "LLM_EMBEDDING_UNAVAILABLE",
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Releases the embedding engine; the next {@link #startEmbeddingOnly()} builds a fresh one. */
    public void stop() {
        LocalEmbeddingEngine embedding;
        synchronized (this) {
            embedding = embeddingEngine;
            embeddingEngine = null;
            embeddingConfigured = false;
        }
        if (embedding != null) {
            embedding.shutdown();
        }
    }
}
