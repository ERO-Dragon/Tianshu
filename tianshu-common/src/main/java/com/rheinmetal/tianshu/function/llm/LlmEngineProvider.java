package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.llm.model.LlmModelPathResolver;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceProvider;
import com.rheinmetal.tianshu.function.llm.runtime.LlmPerformanceSnapshot;
import com.rheinmetal.tianshu.function.llm.service.EmbeddingService;
import com.rheinmetal.tianshu.function.llm.service.LocalEmbeddingEngine;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.InferenceEvent;
import com.rheinmetal.tianshu.libs.llm.FlashAttentionMode;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.libs.llm.LlmContextBudgetPlan;
import com.rheinmetal.tianshu.libs.llm.LlmContextBudgetPolicy;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class LlmEngineProvider {
    private static final String AUTO_DEVICE_ID = "auto";
    private static final String CPU_DEVICE_ID = "cpu";
    private static final String MANUAL_CPU_DEVICE_ID = "cpu:manual";
    /**
     * Embedding runs on the CPU on purpose. A query embedding is one short burst per turn and memory
     * indexing happens in the background, so the lane does not need the card, while the chat model is
     * the one competing for VRAM and needs its weights plus compute buffers there. This holds for both
     * shapes: the local chat server that carries both models, and the embedding-only server used when
     * chat is remote.
     */
    private static final int EMBEDDING_GPU_LAYERS = 0;
    /**
     * Context sizes tried in descending order when the full requested context does not fit GPU
     * memory. It never drops below 4096: below that the assistant loses too much working context
     * to stay useful, so a failed load is preferable to a context that small.
     */
    private static final int[] CONTEXT_FALLBACK_TIERS = {8192, 6144, 4096};

    private final IGameEnvironment env;
    private final LlmConfiguration config;
    private final LlmModelPathResolver modelPaths;
    private final InferenceResourcePolicy resourcePolicy;
    private final Consumer<LlmStatusPayload> inferenceStatusListener;
    private final Executor modelLoadExecutor;
    private volatile LlmPerformanceProvider performanceProvider = LlmPerformanceProvider.UNAVAILABLE;
    private JavaLlamaServer aiService;
    private LocalEmbeddingEngine embeddingEngine;
    private volatile boolean embeddingConfigured;
    private final AtomicLong lifecycleGeneration = new AtomicLong();

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config) {
        this(env, config, InferenceResourcePolicy.systemDefault(), null);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, InferenceResourcePolicy resourcePolicy) {
        this(env, config, resourcePolicy, null);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, Consumer<LlmStatusPayload> inferenceStatusListener) {
        this(env, config, InferenceResourcePolicy.systemDefault(), inferenceStatusListener);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, Consumer<LlmStatusPayload> inferenceStatusListener, Executor modelLoadExecutor) {
        this(env, config, InferenceResourcePolicy.systemDefault(), inferenceStatusListener, modelLoadExecutor);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, InferenceResourcePolicy resourcePolicy, Consumer<LlmStatusPayload> inferenceStatusListener) {
        this(env, config, resourcePolicy, inferenceStatusListener, Runnable::run);
    }

    public LlmEngineProvider(IGameEnvironment env, LlmConfiguration config, InferenceResourcePolicy resourcePolicy, Consumer<LlmStatusPayload> inferenceStatusListener, Executor modelLoadExecutor) {
        this.env = env;
        this.config = config;
        this.modelPaths = new LlmModelPathResolver(config);
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
        this.inferenceStatusListener = inferenceStatusListener == null ? status -> {
        } : inferenceStatusListener;
        this.modelLoadExecutor = modelLoadExecutor == null ? Runnable::run : modelLoadExecutor;
    }

    private JavaLlamaServer createAiService() {
        Path modelPath = modelPaths.resolveChatModel();
        embeddingConfigured = false;
        if (modelPath == null) {
            return null;
        }

        String deviceId = resolveDeviceId(config.getLlmGpuDeviceId());
        boolean cpuOnly = isCpuDevice(deviceId);
        int gpuLayers = cpuOnly ? 0 : resourcePolicy.fullGpuLayers();
        int requestedContextSize = Math.max(1, modelPaths.chatContextSize());
        LlmContextBudgetPolicy budgetPolicy = LlmContextBudgetPolicy.defaults();
        int plannedContextSize = planContextSize(modelPath, requestedContextSize, deviceId, cpuOnly, gpuLayers, budgetPolicy);
        env.diagnostics().publish(DiagnosticEvent.now(
                "module.llm",
                "LLM_RUNTIME_CONFIG",
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.REDACTED,
                Map.of(
                        "model", value(config.getCustomLlmName()),
                        "device", cpuOnly ? "cpu" : value(deviceId),
                        "executionMode", cpuOnly ? "CPU" : "GPU_OFFLOAD_REQUESTED",
                        "gpuLayers", Integer.toString(gpuLayers),
                        "contextSize", Integer.toString(plannedContextSize),
                        "chatThreads", Integer.toString(resourcePolicy.llmGpuHelperThreads()),
                        "taskThreads", Integer.toString(resourcePolicy.llmGpuHelperThreads())
                )
        ));
        env.info("LLM context selected: requested=" + requestedContextSize
                + " planned=" + plannedContextSize
                + " model=" + config.getCustomLlmName()
                + " device=" + (cpuOnly ? "cpu" : deviceId));

        return configureBuilder(modelPath, plannedContextSize, deviceId, cpuOnly, gpuLayers, budgetPolicy).build();
    }

    private JavaLlamaServer.Builder configureBuilder(Path modelPath, int contextSize, String deviceId, boolean cpuOnly, int gpuLayers, LlmContextBudgetPolicy budgetPolicy) {
        int chatThreads = resourcePolicy.llmGpuHelperThreads();
        int taskThreads = resourcePolicy.llmGpuHelperThreads();
        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(blankToNull(config.getCustomLlmName()))
                .modelProfile("auto")
                .contextSize(contextSize)
                .chatThreads(chatThreads)
                .chatMaxQueueSize(positiveOrOne(config.getLlmLibsChatQueueSize()))
                .taskThreads(taskThreads)
                .requestTimeoutSeconds(config.getLlmRequestTimeoutSeconds())
                .cacheTypeK(parseCacheType(config.getLlmCacheTypeK(), KvCacheType.Q8_0))
                .cacheTypeV(parseCacheType(config.getLlmCacheTypeV(), KvCacheType.Q8_0))
                .contextBudgetPolicy(budgetPolicy)
                .gpuLayers(gpuLayers)
                // AUTO keeps the backend's own choice. Forcing ENABLED was tried and the Vulkan
                // backend then failed with "vk::Device::createComputePipeline: ErrorUnknown" on
                // this driver, so flash attention must stay opt-in per backend, not forced.
                .flashAttention(FlashAttentionMode.AUTO)
                .inferenceEventListener(this::handleInferenceEvent);
        if (deviceId != null && !cpuOnly) {
            builder.device(deviceId);
        }
        Path mtpDraftPath = modelPaths.resolveMtpDraftModel();
        if (mtpDraftPath != null) {
            builder.mtpDraftModel(mtpDraftPath.toString());
        }

        Path embeddingPath = modelPaths.resolveEmbeddingModel();
        if (embeddingPath != null) {
            embeddingConfigured = true;
            builder.embeddingModel(embeddingPath.toString())
                    .embeddingContextSize(modelPaths.embeddingContextSize())
                    .embeddingThreads(embeddingThreads())
                    .embeddingAlias(blankToNull(config.getLlmEmbeddingModelName()))
                    // CPU-only embedding; the VRAM budget argument is documented on the constant.
                    .embeddingGpuLayers(EMBEDDING_GPU_LAYERS);
            if (EMBEDDING_GPU_LAYERS > 0 && deviceId != null && !cpuOnly) {
                // Only open a Vulkan device for the embedding model when it actually offloads.
                // Setting embeddingDevice while embeddingGpuLayers is 0 still initialised a Vulkan
                // device, and inline RAG search then failed with
                // "vk::Device::createComputePipeline: ErrorUnknown".
                builder.embeddingDevice(deviceId);
            }
        }

        return builder;
    }

    private int planContextSize(Path modelPath, int requestedContextSize, String deviceId, boolean cpuOnly, int gpuLayers, LlmContextBudgetPolicy budgetPolicy) {
        List<Integer> candidates = contextCandidates(requestedContextSize);
        String lastLimitation = "";
        for (int candidate : candidates) {
            try {
                LlmContextBudgetPlan plan = configureBuilder(modelPath, candidate, deviceId, cpuOnly, gpuLayers, budgetPolicy)
                        .dryRunContextBudget();
                if (plan == null) {
                    env.warn("LLM context dryrun returned no budget plan for context=" + candidate);
                    continue;
                }
                env.info("LLM context dryrun: requested=" + plan.requestedContextSize()
                        + " planned=" + plan.plannedContextSize()
                        + " promptBudget=" + plan.promptTokenBudget()
                        + " memoryLimit=" + plan.memoryContextSize()
                        + " reliable=" + plan.reliable()
                        + formatLimitation(plan));
                if (plan.plannedContextSize() <= 0) {
                    env.warn("LLM context dryrun returned invalid planned context=" + plan.plannedContextSize());
                    continue;
                }
                if (cpuOnly || plan.reliable()) {
                    if (candidate != requestedContextSize) {
                        env.warn("LLM context reduced from " + requestedContextSize
                                + " to " + plan.plannedContextSize() + " to fit GPU memory");
                    }
                    return plan.plannedContextSize();
                }
                lastLimitation = formatLimitation(plan);
                env.warn("LLM context " + candidate + " does not fit GPU memory"
                        + lastLimitation + "; trying a smaller context");
            } catch (Exception failure) {
                env.warn("LLM context dryrun failed for context=" + candidate + ": " + failure.getMessage());
            }
        }
        int smallest = candidates.get(candidates.size() - 1);
        env.warn("No GPU context plan fitted; attempting the load with context=" + smallest + lastLimitation);
        return smallest;
    }

    /** Requested context first, then the descending fallback tiers that are smaller than it. */
    private static List<Integer> contextCandidates(int requestedContextSize) {
        java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>();
        candidates.add(requestedContextSize);
        for (int tier : CONTEXT_FALLBACK_TIERS) {
            if (tier < requestedContextSize) {
                candidates.add(tier);
            }
        }
        return List.copyOf(candidates);
    }

    private static String formatLimitation(LlmContextBudgetPlan plan) {
        if (plan == null || plan.limitation() == null || plan.limitation().isBlank()) {
            return "";
        }
        return " limitation=" + plan.limitation();
    }

    public boolean isEmbeddingConfigured() {
        return embeddingConfigured;
    }

    /**
     * Starts the local engine in embedding-only mode and reports whether an embedding model is now
     * bound. The cloud lane is the only chat channel, so the chat GGUF is never touched here: cloud
     * users usually have not downloaded one, and a missing chat model must not fail the module.
     *
     * <p>libs 1.0.7 accepts a server configuration that carries only an embedding model, so this goes
     * through the public API: {@link LocalEmbeddingEngine} builds a {@link JavaLlamaServer} with
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
        env.warn("[LLM] Vector RAG stays disabled: " + detail + ". Chat is unaffected.");
        try {
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.llm",
                    "LLM_EMBEDDING_UNAVAILABLE",
                    DiagnosticSeverity.WARN,
                    DiagnosticPrivacy.REDACTED,
                    Map.of(
                            "reason", detail,
                            "embeddingModel", value(config.getLlmEmbeddingModelName()),
                            "embeddingConfigured", "false"
                    )
            ));
        } catch (RuntimeException diagnosticFailure) {
            env.warn("Failed to publish LLM embedding diagnostics: " + diagnosticFailure.getMessage());
        }
    }

    private int positiveOrOne(int value) {
        return Math.max(1, value);
    }

    private KvCacheType parseCacheType(String value, KvCacheType fallback) {
        try {
            return KvCacheType.parse(value);
        } catch (Exception e) {
            env.warn("Invalid LLM KV cache type: " + value + ", fallback to " + fallback.wireName());
            return fallback;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isCpuDevice(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim();
        return CPU_DEVICE_ID.equalsIgnoreCase(normalized) || MANUAL_CPU_DEVICE_ID.equalsIgnoreCase(normalized);
    }

    private static boolean isAutoDevice(String value) {
        return value == null || value.isBlank() || AUTO_DEVICE_ID.equalsIgnoreCase(value.trim());
    }

    private static String resolveDeviceId(String configuredDeviceId) {
        if (!isAutoDevice(configuredDeviceId)) {
            return blankToNull(configuredDeviceId);
        }
        List<String> gpuIds = queryNvidiaGpuIds();
        if (gpuIds.size() >= 2) {
            return gpuIds.get(1);
        }
        if (gpuIds.size() == 1) {
            return gpuIds.get(0);
        }
        return CPU_DEVICE_ID;
    }

    private static List<String> queryNvidiaGpuIds() {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "nvidia-smi",
                    "--query-gpu=index",
                    "--format=csv,noheader,nounits"
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (output.isBlank()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (String line : output.lines().toList()) {
                String id = line == null ? "" : line.trim();
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            return List.copyOf(ids);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void handleInferenceEvent(InferenceEvent event) {
        if (event == null) {
            return;
        }
        try {
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.llm",
                    "LLM_INFERENCE_EVENT",
                    DiagnosticSeverity.DEBUG,
                    DiagnosticPrivacy.REDACTED,
                    Map.of(
                            "taskId", value(event.getTaskId()),
                            "taskType", event.getTaskType() == null ? "" : event.getTaskType().name(),
                            "lane", event.getLane() == null ? "" : event.getLane().wireName(),
                            "eventType", event.getType() == null ? "" : event.getType().name(),
                            "message", value(event.getMessage()),
                            "replayCharacters", Integer.toString(Math.max(0, event.getReplayCharacters())),
                            "generatedTokens", Integer.toString(Math.max(0, event.getGeneratedTokens()))
                    )
            ));
            inferenceStatusListener.accept(toStatus(event));
        } catch (Exception e) {
            env.warn("Failed to publish LLM inference event: " + e.getMessage());
        }
    }

    private void publishRuntimeReadyDiagnostics(JavaLlamaServer service) {
        if (service == null) {
            return;
        }
        var capabilities = service.getRuntimeCapabilities();
        var contextPlan = service.getContextBudgetPlan();
        env.diagnostics().publish(DiagnosticEvent.now(
                "module.llm",
                "LLM_RUNTIME_READY",
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.REDACTED,
                Map.of(
                        "ready", Boolean.toString(service.isReady()),
                        "supportsThinking", Boolean.toString(capabilities != null && capabilities.supportsThinking()),
                        "supportsMtp", Boolean.toString(capabilities != null && capabilities.supportsMtp()),
                        "contextSize", Integer.toString(contextPlan == null ? 0 : contextPlan.plannedContextSize())
                )
        ));
    }

    private static String value(String text) {
        return text == null ? "" : text;
    }

    private static LlmStatusPayload toStatus(InferenceEvent event) {
        Throwable error = event.getError();
        return new LlmStatusPayload(
                event.getTaskId(),
                event.getTaskType() == null ? "" : event.getTaskType().name(),
                event.getLane() == null ? "" : event.getLane().wireName(),
                event.getType() == null ? "" : event.getType().name(),
                event.getPriority(),
                event.getMessage(),
                event.getReplayCharacters(),
                event.getGeneratedTokens(),
                error == null ? "" : error.getMessage(),
                System.currentTimeMillis()
        );
    }

    public boolean isAiServiceAvailable() {
        return currentAiService() != null;
    }

    public JavaLlamaServer getAiService() {
        ensureAiService();
        return aiService;
    }

    public JavaLlamaServer currentAiService() {
        return aiService;
    }

    public void startAsync(Runnable onLoadingStarted, Runnable onReady, Runnable onFailed, Runnable onLoadingFinished) {
        long generation = lifecycleGeneration.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            JavaLlamaServer service = null;
            boolean loadingStarted = false;
            try {
                if (currentAiService() == null && modelPaths.resolveChatModel() == null) {
                    if (onFailed != null) {
                        onFailed.run();
                    }
                    return;
                }
                loadingStarted = true;
                runLoadingCallback(onLoadingStarted);
                publishLoadStartDiagnostics();
                service = ensureAiService();
                if (service == null) {
                    if (onFailed != null) {
                        onFailed.run();
                    }
                    return;
                }
                service.start();
                publishRuntimeReadyDiagnostics(service);
                synchronized (this) {
                    if (generation != lifecycleGeneration.get() || aiService != service) {
                        service.shutdown();
                        return;
                    }
                }
                if (onReady != null) {
                    onReady.run();
                }
            } catch (Exception e) {
                if (service != null && generation != lifecycleGeneration.get()) {
                    try {
                        service.shutdown();
                    } catch (Exception ignored) {
                    }
                    return;
                }
                publishLoadFailureDiagnostics(e);
                env.error("Failed to start AI service", e);
                if (onFailed != null) {
                    onFailed.run();
                }
            } finally {
                if (loadingStarted) {
                    runLoadingCallback(onLoadingFinished);
                }
            }
        }, modelLoadExecutor);
    }

    private void publishLoadFailureDiagnostics(Throwable failure) {
        try {
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.llm",
                    "LLM_RUNTIME_LOAD_FAILED",
                    DiagnosticSeverity.ERROR,
                    DiagnosticPrivacy.REDACTED,
                    loadFailureAttributes(failure, performanceProvider.performanceSnapshot())
            ));
        } catch (RuntimeException diagnosticFailure) {
            env.warn("Failed to publish LLM load failure diagnostics: " + diagnosticFailure.getMessage());
        }
    }

    /** Adds the GPU snapshot to the memory picture so a VRAM shortfall is not mistaken for a RAM one. */
    static Map<String, String> loadFailureAttributes(Throwable failure, LlmPerformanceSnapshot performance) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>(loadFailureAttributes(failure));
        attributes.putAll(gpuSnapshotAttributes(performance));
        return attributes;
    }

    /** GPU memory and utilisation as observed right now. */
    static Map<String, String> gpuSnapshotAttributes(LlmPerformanceSnapshot performance) {
        LlmPerformanceSnapshot snapshot = performance == null ? LlmPerformanceSnapshot.unavailable() : performance;
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("gpuSnapshotAvailable", Boolean.toString(snapshot.available()));
        attributes.put("gpuUsesGpu", Boolean.toString(snapshot.llmUsesGpu()));
        attributes.put("gpuVramUsedBytes", Long.toString(snapshot.vramUsedBytes()));
        attributes.put("gpuVramTotalBytes", Long.toString(snapshot.vramTotalBytes()));
        attributes.put("gpuVramFreeBytes", Long.toString(Math.max(0L, snapshot.vramTotalBytes() - snapshot.vramUsedBytes())));
        attributes.put("gpuUtilization", Double.toString(snapshot.gpuUtilization()));
        return attributes;
    }

    /**
     * Records the resources available immediately BEFORE loading starts. A failure diagnostic is
     * sampled after the native loader has rolled its allocation back, so only this snapshot shows
     * the headroom the load actually had to fit into.
     */
    private void publishLoadStartDiagnostics() {
        try {
            java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
            attributes.put("model", value(config.getCustomLlmName()));
            java.lang.management.OperatingSystemMXBean systemBean = ManagementFactory.getOperatingSystemMXBean();
            if (systemBean instanceof com.sun.management.OperatingSystemMXBean memoryBean) {
                attributes.put("systemAvailableMemoryBytes", Long.toString(memoryBean.getFreeMemorySize()));
                attributes.put("systemTotalMemoryBytes", Long.toString(memoryBean.getTotalMemorySize()));
            }
            attributes.putAll(gpuSnapshotAttributes(performanceProvider.performanceSnapshot()));
            env.diagnostics().publish(DiagnosticEvent.now(
                    "module.llm",
                    "LLM_RUNTIME_LOAD_START",
                    DiagnosticSeverity.INFO,
                    DiagnosticPrivacy.REDACTED,
                    attributes
            ));
        } catch (RuntimeException failure) {
            env.warn("Failed to publish LLM load start diagnostics: " + failure.getMessage());
        }
    }

    static Map<String, String> loadFailureAttributes(Throwable failure) {
        Runtime runtime = Runtime.getRuntime();
        long heapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
        java.lang.management.OperatingSystemMXBean systemBean = ManagementFactory.getOperatingSystemMXBean();
        long systemAvailableMemoryBytes = systemBean instanceof com.sun.management.OperatingSystemMXBean memoryBean
                ? memoryBean.getFreeMemorySize()
                : -1L;
        long systemTotalMemoryBytes = systemBean instanceof com.sun.management.OperatingSystemMXBean memoryBean
                ? memoryBean.getTotalMemorySize()
                : -1L;
        return Map.of(
                "failureChain", failureChain(failure),
                "jvmHeapUsedBytes", Long.toString(heapUsedBytes),
                "jvmHeapCommittedBytes", Long.toString(runtime.totalMemory()),
                "jvmHeapMaxBytes", Long.toString(runtime.maxMemory()),
                "systemAvailableMemoryBytes", Long.toString(systemAvailableMemoryBytes),
                "systemTotalMemoryBytes", Long.toString(systemTotalMemoryBytes)
        );
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

    private void runLoadingCallback(Runnable callback) {
        if (callback == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException failure) {
            env.warn("LLM model loading observer failed: " + failure.getMessage());
        }
    }

    /**
     * Binds the platform performance probe used to enrich load-failure diagnostics with GPU memory.
     * Model loading only fails with the generic native message "Cannot load model", so recording
     * VRAM next to system memory is what distinguishes a VRAM shortfall from a RAM shortfall.
     */
    public void bindPerformanceProvider(LlmPerformanceProvider provider) {
        this.performanceProvider = provider == null ? LlmPerformanceProvider.UNAVAILABLE : provider;
    }

    public void stop() {
        lifecycleGeneration.incrementAndGet();
        JavaLlamaServer service;
        LocalEmbeddingEngine embedding;
        synchronized (this) {
            service = aiService;
            aiService = null;
            embedding = embeddingEngine;
            embeddingEngine = null;
            embeddingConfigured = false;
        }
        if (service != null) {
            service.shutdown();
        }
        if (embedding != null) {
            embedding.shutdown();
        }
    }

    private synchronized JavaLlamaServer ensureAiService() {
        if (aiService == null) {
            aiService = createAiService();
        }
        return aiService;
    }
}
