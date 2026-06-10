package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class LlmEngineProvider {
    static final int MIN_TASK_HOT_SUSPEND_SLOTS = 0;
    static final int MAX_TASK_HOT_SUSPEND_SLOTS = 5;

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private JavaLlamaServer aiService;

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
    }

    private JavaLlamaServer createAiService() {
        Path modelPath = config.getLlmGgufFilePath();
        if (modelPath == null) {
            env.warn("LLM model path not configured, AI service will not be available");
            return null;
        }

        int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
        int chatThreads = Math.max(1, processors - 1);
        int taskThreads = Math.max(1, processors / 2);
        int gpuLayers = gpuLayersFromPercent(config.getLlmGpuLayerPercent());

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(modelPath.toString())
                .modelAlias(blankToNull(config.getCustomLlmName()))
                .modelProfile("auto")
                .chatContext(config.getLlmChatContextSize())
                .chatThreads(chatThreads)
                .chatMaxQueueSize(positiveOrOne(config.getLlmLibsChatQueueSize()))
                .taskContext(config.getLlmTaskContextSize())
                .taskThreads(taskThreads)
                .taskMaxQueueSize(taskHotSuspendSlots(config.getLlmTaskHotSuspendSlots()))
                .taskSuspendOnChat(config.isLlmTaskSuspendOnChatEnabled())
                .requestTimeoutSeconds(config.getLlmRequestTimeoutSeconds())
                .cacheTypeK(parseCacheType(config.getLlmCacheTypeK(), KvCacheType.Q8_0))
                .cacheTypeV(parseCacheType(config.getLlmCacheTypeV(), KvCacheType.Q8_0))
                .gpuLayers(gpuLayers);

        Path embeddingPath = config.getLlmEmbeddingGgufFilePath();
        if (embeddingPath != null) {
            builder.embeddingModel(embeddingPath.toString())
                    .embeddingContextSize(config.getLlmEmbeddingContextSize())
                    .embeddingThreads(Math.max(1, processors / 2))
                    .embeddingAlias(blankToNull(config.getLlmEmbeddingModelName()))
                    .embeddingGpuLayers(gpuLayers);
        }

        return builder.build();
    }

    private int gpuLayersFromPercent(int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped == 0) {
            return 0;
        }
        return Math.max(1, Math.round(999f * clamped / 100f));
    }

    private int positiveOrOne(int value) {
        return Math.max(1, value);
    }

    static int taskHotSuspendSlots(int value) {
        return Math.max(MIN_TASK_HOT_SUSPEND_SLOTS, Math.min(MAX_TASK_HOT_SUSPEND_SLOTS, value));
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

    public boolean isAiServiceAvailable() {
        ensureAiService();
        return aiService != null;
    }

    public JavaLlamaServer getAiService() {
        ensureAiService();
        return aiService;
    }

    public void startAsync(Runnable onReady, Runnable onFailed) {
        JavaLlamaServer service = ensureAiService();
        if (service == null) {
            onFailed.run();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
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
