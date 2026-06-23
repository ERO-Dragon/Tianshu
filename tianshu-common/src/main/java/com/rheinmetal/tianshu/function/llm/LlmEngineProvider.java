package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.InferenceEvent;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class LlmEngineProvider {
    private static final String AUTO_DEVICE_ID = "auto";
    private static final String CPU_DEVICE_ID = "cpu";
    private static final String MANUAL_CPU_DEVICE_ID = "cpu:manual";

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final InferenceResourcePolicy resourcePolicy;
    private final Consumer<LlmStatusPayload> inferenceStatusListener;
    private JavaLlamaServer aiService;

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config) {
        this(env, config, InferenceResourcePolicy.systemDefault(), null);
    }

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config, InferenceResourcePolicy resourcePolicy) {
        this(env, config, resourcePolicy, null);
    }

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config, Consumer<LlmStatusPayload> inferenceStatusListener) {
        this(env, config, InferenceResourcePolicy.systemDefault(), inferenceStatusListener);
    }

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config, InferenceResourcePolicy resourcePolicy, Consumer<LlmStatusPayload> inferenceStatusListener) {
        this.env = env;
        this.config = config;
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
        this.inferenceStatusListener = inferenceStatusListener == null ? status -> {
        } : inferenceStatusListener;
    }

    private JavaLlamaServer createAiService() {
        Path modelPath = config.getLlmGgufFilePath();
        if (modelPath == null) {
            return null;
        }

        int processors = resourcePolicy.processors();
        int chatThreads = resourcePolicy.llmGpuHelperThreads();
        int taskThreads = resourcePolicy.llmGpuHelperThreads();
        String deviceId = resolveDeviceId(config.getLlmGpuDeviceId());
        boolean cpuOnly = isCpuDevice(deviceId);
        int gpuLayers = cpuOnly ? 0 : resourcePolicy.fullGpuLayers();
        int contextSize = Math.max(1, config.getLlmContextSize());
        env.info("LLM context selected: context=" + contextSize
                + " model=" + config.getCustomLlmName()
                + " device=" + (cpuOnly ? "cpu" : deviceId));

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(blankToNull(config.getCustomLlmName()))
                .modelProfile("auto")
                .contextSize(contextSize)
                .chatThreads(chatThreads)
                .chatMaxQueueSize(positiveOrOne(config.getLlmLibsChatQueueSize()))
                .taskThreads(taskThreads)
                .taskSuspendOnChat(config.isLlmTaskSuspendOnChatEnabled())
                .requestTimeoutSeconds(config.getLlmRequestTimeoutSeconds())
                .cacheTypeK(parseCacheType(config.getLlmCacheTypeK(), KvCacheType.Q8_0))
                .cacheTypeV(parseCacheType(config.getLlmCacheTypeV(), KvCacheType.Q8_0))
                .gpuLayers(gpuLayers)
                .inferenceEventListener(this::handleInferenceEvent);
        if (deviceId != null && !cpuOnly) {
            builder.device(deviceId);
        }

        Path embeddingPath = config.getLlmEmbeddingGgufFilePath();
        if (embeddingPath != null) {
            builder.embeddingModel(embeddingPath.toString())
                    .embeddingContextSize(config.getLlmEmbeddingContextSize())
                    .embeddingThreads(Math.max(1, Math.min(processors, resourcePolicy.llmGpuHelperThreads())))
                    .embeddingAlias(blankToNull(config.getLlmEmbeddingModelName()))
                    .embeddingGpuLayers(gpuLayers);
            if (deviceId != null && !cpuOnly) {
                builder.embeddingDevice(deviceId);
            }
        }

        return builder.build();
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
            inferenceStatusListener.accept(toStatus(event));
        } catch (Exception e) {
            env.warn("Failed to publish LLM inference event: " + e.getMessage());
        }
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

    public void startAsync(Runnable onReady, Runnable onFailed) {
        CompletableFuture.runAsync(() -> {
            try {
                JavaLlamaServer service = ensureAiService();
                if (service == null) {
                    if (onFailed != null) {
                        onFailed.run();
                    }
                    return;
                }
                service.start();
                if (onReady != null) {
                    onReady.run();
                }
            } catch (Exception e) {
                env.error("Failed to start AI service", e);
                if (onFailed != null) {
                    onFailed.run();
                }
            }
        });
    }

    public void stop() {
        JavaLlamaServer service = aiService;
        aiService = null;
        if (service != null) {
            service.shutdown();
        }
    }

    private synchronized JavaLlamaServer ensureAiService() {
        if (aiService == null) {
            aiService = createAiService();
        }
        return aiService;
    }
}
