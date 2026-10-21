package com.rheinmetal.tianshu.function.llm.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Embedding-only view of the local llama.cpp engine, exposed as an {@link EmbeddingService}.
 *
 * <p>The cloud lane chats remotely but still needs local vectors for RAG, and cloud users usually
 * never download a chat GGUF. That shape is supported by the libs public API since 1.0.7: a
 * {@link JavaLlamaServer} configured with an embedding model and no chat model passes
 * {@code ServerConfig} validation, and {@link JavaLlamaServer#start()} then loads only the embedding
 * GGUF. The whole vector lane therefore runs through the public server API — builder → {@code start()}
 * → {@link JavaLlamaServer#embed(String)} — instead of opening a libs sub-engine directly.
 *
 * <p>Readiness is deliberately {@link JavaLlamaServer#hasEmbeddingEngine()} and nothing else:
 * {@code isReady()} only reports that the <em>chat</em> lane can generate, so it is permanently false
 * in this mode; {@code isStarted()} only says the server was constructed and started. Only
 * {@code hasEmbeddingEngine()} answers "can this produce vectors right now".
 *
 * <p>{@link JavaLlamaServer} does not expose the embedding dimension, so it is learned from the first
 * returned vector — the same contract {@link LlmEmbeddingServiceAdapter} already has for the local
 * chat lane.
 *
 * <p>The load is deferred to {@link #warmUp(Executor)} (queued on the model-load lane) or to the first
 * embedding call, so starting the LLM module never blocks on a model read. A failed load is remembered
 * and reported once instead of retrying the disk on every RAG write.
 */
public final class LocalEmbeddingEngine implements EmbeddingService {
    private static final String MODULE_ID = "module.llm";
    private static final String UNAVAILABLE_MESSAGE = "LLM_EMBEDDING_UNAVAILABLE";

    private final IGameEnvironment env;
    private final Path modelPath;
    private final int contextSize;
    private final int threads;
    private final int gpuLayers;
    private final String alias;
    private final Object loadLock = new Object();
    private volatile JavaLlamaServer server;
    private volatile int dimension = -1;
    private volatile String loadFailure = "";
    private volatile boolean closed;

    /**
     * @param gpuLayers layers offloaded for the embedding model; 0 keeps it on the CPU, which is what
     *                  {@code LlmEngineProvider} asks for so the chat model keeps the whole GPU budget
     * @param alias     optional embedding model alias, falling back to the file name inside libs
     */
    public LocalEmbeddingEngine(
            IGameEnvironment env,
            Path modelPath,
            int contextSize,
            int threads,
            int gpuLayers,
            String alias
    ) {
        this.env = Objects.requireNonNull(env, "env");
        this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
        this.contextSize = contextSize;
        this.threads = threads;
        this.gpuLayers = gpuLayers;
        this.alias = alias == null || alias.isBlank() ? null : alias.trim();
    }

    /** Queues the model read so the module start path stays non-blocking. */
    public void warmUp(Executor executor) {
        Executor target = executor == null ? Runnable::run : executor;
        try {
            target.execute(() -> {
                try {
                    ensureServer();
                } catch (Exception failure) {
                    // The failure is already published once; RAG writes degrade instead of failing the lane.
                    env.warn("[LLM] Local embedding engine is unavailable: " + failure.getMessage());
                }
            });
        } catch (RuntimeException rejected) {
            env.warn("[LLM] Failed to queue the local embedding model load: " + rejected.getMessage());
        }
    }

    @Override
    public float[] embed(String text) throws Exception {
        float[] vector = ensureServer().embed(text);
        rememberDimension(vector);
        return vector;
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        float[][] vectors = ensureServer().embed(texts);
        if (vectors != null) {
            for (float[] vector : vectors) {
                rememberDimension(vector);
                if (dimension > 0) {
                    break;
                }
            }
        }
        return vectors;
    }

    /** -1 until a vector has been produced, because the libs server exposes no dimension getter. */
    @Override
    public int getEmbeddingDimension() {
        return dimension;
    }

    public void shutdown() {
        JavaLlamaServer stopped;
        synchronized (loadLock) {
            closed = true;
            stopped = server;
            server = null;
        }
        dimension = -1;
        loadFailure = "";
        if (stopped == null) {
            return;
        }
        try {
            // libs shutdown is null-safe and repeatable, so a second call after stop() is harmless.
            stopped.shutdown();
        } catch (RuntimeException failure) {
            env.warn("[LLM] Failed to shut the local embedding engine down: " + failure.getMessage());
        }
    }

    private JavaLlamaServer ensureServer() throws Exception {
        JavaLlamaServer started = server;
        if (hasEmbeddingEngine(started)) {
            return started;
        }
        synchronized (loadLock) {
            if (closed) {
                throw new IllegalStateException(UNAVAILABLE_MESSAGE);
            }
            if (!loadFailure.isEmpty()) {
                throw new IllegalStateException(UNAVAILABLE_MESSAGE + ": " + loadFailure);
            }
            if (hasEmbeddingEngine(server)) {
                return server;
            }
            try {
                JavaLlamaServer created = buildEmbeddingOnlyServer();
                // build() only validates the configuration; start() is what loads the GGUF.
                created.start();
                if (!created.hasEmbeddingEngine()) {
                    created.shutdown();
                    throw new IllegalStateException("the embedding model reported no loaded engine after start");
                }
                server = created;
                publishReady();
                return created;
            } catch (Exception failure) {
                // start() already rolled a partial load back and left the server unstarted, so the
                // server instance is dropped instead of being shut down a second time.
                loadFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                publishLoadFailure(failure);
                throw failure;
            }
        }
    }

    /**
     * The embedding-only server shape: an embedding model and nothing else. No {@code model(...)} is
     * set, so libs loads no chat engine, and no {@code embeddingDevice} is set either — with zero
     * offloaded layers a device would only add a Vulkan initialisation that has failed on this driver
     * before ("vk::Device::createComputePipeline: ErrorUnknown").
     */
    private JavaLlamaServer buildEmbeddingOnlyServer() {
        return JavaLlamaServer.builder()
                .embeddingModel(modelPath.toString())
                .embeddingContextSize(contextSize)
                .embeddingThreads(threads)
                .embeddingGpuLayers(gpuLayers)
                .embeddingAlias(alias)
                .build();
    }

    /** Model-loaded check for this mode; {@code isReady()} is chat-only and stays false here. */
    private static boolean hasEmbeddingEngine(JavaLlamaServer candidate) {
        return candidate != null && candidate.hasEmbeddingEngine();
    }

    private void rememberDimension(float[] vector) {
        if (dimension < 0 && vector != null && vector.length > 0) {
            dimension = vector.length;
        }
    }

    private void publishReady() {
        env.info("[LLM] Local embedding engine loaded in embedding-only mode: model=" + modelPath.getFileName()
                + " threads=" + threads
                + " context=" + contextSize);
        publish("LLM_EMBEDDING_READY", DiagnosticSeverity.INFO, attributes());
    }

    private void publishLoadFailure(Throwable failure) {
        Map<String, String> attributes = attributes();
        attributes.put("failureChain", failureChain(failure));
        publish("LLM_EMBEDDING_LOAD_FAILED", DiagnosticSeverity.ERROR, attributes);
        env.warn("[LLM] Failed to load the local embedding model " + modelPath.getFileName()
                + ": " + failure.getMessage()
                + ". Vector RAG stays disabled for this session.");
    }

    private Map<String, String> attributes() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("mode", "embedding-only");
        attributes.put("model", modelPath.getFileName() == null ? "" : modelPath.getFileName().toString());
        attributes.put("contextSize", Integer.toString(contextSize));
        attributes.put("embeddingThreads", Integer.toString(threads));
        attributes.put("gpuLayers", Integer.toString(gpuLayers));
        return attributes;
    }

    private void publish(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        try {
            env.diagnostics().publish(DiagnosticEvent.now(MODULE_ID, code, severity, DiagnosticPrivacy.REDACTED, attributes));
        } catch (RuntimeException diagnosticFailure) {
            env.warn("[LLM] Failed to publish embedding diagnostics: " + diagnosticFailure.getMessage());
        }
    }

    private static String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 8) {
            if (!chain.isEmpty()) {
                chain.append(" <- ");
            }
            chain.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                chain.append(": ").append(message);
            }
            current = current.getCause();
            depth++;
        }
        return chain.toString();
    }
}
