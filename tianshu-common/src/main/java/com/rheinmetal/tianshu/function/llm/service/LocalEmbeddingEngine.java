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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BiConsumer;

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
 * embedding call, so starting the LLM module never blocks on a model read. That load is also where the
 * CPU/GPU position is decided: the requested position is tried first, and only a position that
 * <em>produced a vector</em> is accepted, because a backend that loads but cannot compute is exactly
 * the failure verification catches. A card that fails hands the lane to the CPU once; a CPU that fails
 * as well means the model or its file is broken, and that failure is remembered and reported once
 * instead of retrying the disk on every RAG write. Either way the outcome is reported through the load
 * listener, so no caller has to poll for it.
 */
public final class LocalEmbeddingEngine implements EmbeddingService {
    private static final String MODULE_ID = "module.llm";
    private static final String UNAVAILABLE_MESSAGE = "LLM_EMBEDDING_UNAVAILABLE";
    /** One short inference: what turns "the model loaded" into "the model can embed". */
    private static final String WARM_UP_TEXT = "tianshu embedding warm-up";

    private final IGameEnvironment env;
    private final Path modelPath;
    private final int contextSize;
    private final int threads;
    /** Layers the player asked for; 0 means the CPU is the only accepted position. */
    private final int requestedGpuLayers;
    private final String alias;
    private final BiConsumer<LocalEmbeddingEngine, EmbeddingLoadReport> loadListener;
    private final Object loadLock = new Object();
    /**
     * Serialises inference against teardown.
     *
     * <p>The libs server frees the GGUF only once its embedding worker has stopped, and when the worker
     * does not stop inside its grace period it skips the free and marks itself shut down for good —
     * native memory that no collector can reach. Stopping while an inference is in flight is therefore
     * the one way this lane can leak, and teardown waits here for exactly that reason: when it reaches
     * libs, the worker is idle by construction.
     */
    private final ReentrantReadWriteLock inferenceGate = new ReentrantReadWriteLock();
    private volatile JavaLlamaServer server;
    /** Layers the running server was started with; differs from the request after a fallback. */
    private volatile int gpuLayers;
    private volatile int dimension = -1;
    private volatile String loadFailure = "";
    private volatile boolean closed;

    /**
     * @param requestedGpuLayers layers to offload first; 0 keeps the lane on the CPU and leaves no
     *                           fallback, because the CPU is already the last position
     * @param alias              optional embedding model alias, falling back to the file name inside libs
     * @param loadListener       told once how the load ended, so a caller can bind or refuse the lane
     *                           without polling it; may be null
     */
    public LocalEmbeddingEngine(
            IGameEnvironment env,
            Path modelPath,
            int contextSize,
            int threads,
            int requestedGpuLayers,
            String alias,
            BiConsumer<LocalEmbeddingEngine, EmbeddingLoadReport> loadListener
    ) {
        this.env = Objects.requireNonNull(env, "env");
        this.modelPath = Objects.requireNonNull(modelPath, "modelPath");
        this.contextSize = contextSize;
        this.threads = threads;
        this.requestedGpuLayers = Math.max(0, requestedGpuLayers);
        this.gpuLayers = this.requestedGpuLayers;
        this.alias = alias == null || alias.isBlank() ? null : alias.trim();
        this.loadListener = loadListener == null ? (engine, report) -> { } : loadListener;
    }

    /**
     * Queues the load — position check included — on the given lane, so the caller never waits on a
     * model read: the outcome arrives through the listener instead of as a return value.
     */
    public void warmUp(Executor executor) {
        Executor target = executor == null ? Runnable::run : executor;
        try {
            target.execute(() -> {
                try {
                    ensureServer();
                } catch (Exception failure) {
                    // A lane that was stopped on purpose is not a failure: the restart that stopped it
                    // already queued its own engine on this lane.
                    if (!closed) {
                        // The failure is already published once; RAG writes degrade instead of failing the lane.
                        env.warn("[LLM] Local embedding engine is unavailable: " + describe(failure));
                    }
                }
            });
        } catch (RuntimeException rejected) {
            env.warn("[LLM] Failed to queue the local embedding model load: " + rejected.getMessage());
        }
    }

    @Override
    public float[] embed(String text) throws Exception {
        Lock running = inferenceGate.readLock();
        running.lock();
        try {
            float[] vector = ensureServer().embed(text);
            rememberDimension(vector);
            return vector;
        } finally {
            running.unlock();
        }
    }

    @Override
    public float[][] embed(List<String> texts) throws Exception {
        Lock running = inferenceGate.readLock();
        running.lock();
        try {
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
        } finally {
            running.unlock();
        }
    }

    /** -1 until a vector has been produced, because the libs server exposes no dimension getter. */
    @Override
    public int getEmbeddingDimension() {
        return dimension;
    }

    /**
     * Releases the engine and everything it holds in native memory.
     *
     * <p>The exclusive gate is what makes the release complete: libs stops accepting work, waits for
     * its worker, and only then frees the model, so no inference may still be running when this is
     * called. Repeatable, and a no-op when nothing was ever loaded.
     */
    public void shutdown() {
        Lock draining = inferenceGate.writeLock();
        draining.lock();
        try {
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
        } finally {
            draining.unlock();
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
            return loadLocked();
        }
    }

    /**
     * Loads the model once, at the requested position, and reports how that ended.
     *
     * <p>Each attempt is verified with one embedding, so "loaded" never stands in for "works". A
     * requested position that fails is only remembered as a failure when the CPU cannot embed either:
     * a card that could not run the model leaves the lane working on the CPU, while a broken model
     * leaves it off.
     */
    private JavaLlamaServer loadLocked() throws Exception {
        Exception failure;
        try {
            JavaLlamaServer loaded = startAtPosition(requestedGpuLayers);
            report(EmbeddingLoadReport.ready());
            return loaded;
        } catch (Exception requestedFailure) {
            failure = requestedFailure;
        }
        String reason = describe(failure);
        if (requestedGpuLayers > 0) {
            env.warn("[LLM] The embedding model could not run on the card (" + reason
                    + "), retrying on the CPU");
            publish("LLM_EMBEDDING_DEGRADED", DiagnosticSeverity.WARN, positionAttributes(reason));
            try {
                JavaLlamaServer loaded = startAtPosition(0);
                report(EmbeddingLoadReport.degraded(reason));
                return loaded;
            } catch (Exception cpuFailure) {
                failure = cpuFailure;
                reason = describe(cpuFailure);
            }
        }
        loadFailure = reason;
        publishLoadFailure(failure);
        report(EmbeddingLoadReport.unavailable(reason));
        throw failure;
    }

    /**
     * Starts and verifies one position. A server that started owns native memory, so it is released
     * before the next position is attempted; only a verified server is published to callers.
     */
    private JavaLlamaServer startAtPosition(int layers) throws Exception {
        JavaLlamaServer created = buildEmbeddingOnlyServer(layers);
        boolean started = false;
        try {
            // build() only validates the configuration; start() is what loads the GGUF.
            created.start();
            started = true;
            if (!created.hasEmbeddingEngine()) {
                throw new IllegalStateException("the embedding model reported no loaded engine after start");
            }
            rememberDimension(created.embed(WARM_UP_TEXT));
        } catch (Exception failure) {
            if (started) {
                releaseQuietly(created);
            }
            throw failure;
        }
        server = created;
        gpuLayers = layers;
        publishReady();
        return created;
    }

    private void releaseQuietly(JavaLlamaServer rejected) {
        try {
            rejected.shutdown();
        } catch (RuntimeException failure) {
            env.warn("[LLM] Failed to release a rejected embedding engine: " + describe(failure));
        }
    }

    private void report(EmbeddingLoadReport report) {
        try {
            loadListener.accept(this, report);
        } catch (RuntimeException listenerFailure) {
            env.warn("[LLM] Failed to report the embedding load outcome: " + describe(listenerFailure));
        }
    }

    /**
     * The embedding-only server shape: an embedding model and nothing else. No {@code model(...)} is
     * set, so libs loads no chat engine, and no {@code embeddingDevice} is set either — with zero
     * offloaded layers a device would only add a Vulkan initialisation that has failed on this driver
     * before ("vk::Device::createComputePipeline: ErrorUnknown").
     */
    private JavaLlamaServer buildEmbeddingOnlyServer(int layers) {
        return JavaLlamaServer.builder()
                .embeddingModel(modelPath.toString())
                .embeddingContextSize(contextSize)
                .embeddingThreads(threads)
                .embeddingGpuLayers(layers)
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

    /** The position in use is not the one that was asked for, so both numbers are recorded. */
    private Map<String, String> positionAttributes(String reason) {
        Map<String, String> attributes = attributes();
        attributes.put("requestedGpuLayers", Integer.toString(requestedGpuLayers));
        attributes.put("reason", reason == null ? "" : reason);
        return attributes;
    }

    private void publish(String code, DiagnosticSeverity severity, Map<String, String> attributes) {
        try {
            env.diagnostics().publish(DiagnosticEvent.now(MODULE_ID, code, severity, DiagnosticPrivacy.REDACTED, attributes));
        } catch (RuntimeException diagnosticFailure) {
            env.warn("[LLM] Failed to publish embedding diagnostics: " + diagnosticFailure.getMessage());
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName() + ": " + message.trim();
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
