package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public final class LlmEngineProvider {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final JavaLlamaServer aiService;

    public LlmEngineProvider(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
        this.config = config;
        this.aiService = createAiService();
    }

    private JavaLlamaServer createAiService() {
        Path modelPath = config.getLlmGgufFilePath();
        if (modelPath == null) {
            env.warn("LLM model path not configured, AI service will not be available");
            return null;
        }

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .chatModel(modelPath.toString())
                .chatContext(config.getLlmContextSize())
                .chatThreads(Runtime.getRuntime().availableProcessors())
                .gpuLayers(999);

        Path embeddingPath = config.getLlmEmbeddingGgufFilePath();
        if (embeddingPath != null) {
            builder.embeddingModel(embeddingPath.toString())
                   .embeddingContext(config.getLlmEmbeddingContextSize())
                   .embeddingGpuLayers(999);
        }

        return builder.build();
    }

    public boolean isAiServiceAvailable() {
        return aiService != null;
    }

    public JavaLlamaServer getAiService() {
        return aiService;
    }

    public void startAsync(Runnable onReady, Runnable onFailed) {
        if (aiService == null) {
            onFailed.run();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                aiService.start();
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
        if (aiService != null) {
            aiService.shutdown();
        }
    }
}
