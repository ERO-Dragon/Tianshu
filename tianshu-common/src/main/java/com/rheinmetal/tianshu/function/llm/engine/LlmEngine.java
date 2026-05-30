package com.rheinmetal.tianshu.function.llm.engine;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.llm.inference.LlmInvocationLane;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagHit;
import com.rheinmetal.tianshu.function.llm.inference.LlmRagRoutingContext;
import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.web.ChatController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class LlmEngine {
    public enum FinishReason {
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public record ChatMessage(String role, String content) {
        public ChatMessage {
            if (role == null || role.isBlank()) role = "user";
            if (content == null) content = "";
        }
    }

    private final IGameEnvironment env;
    private final JavaLlamaServer aiService;
    private final AtomicLong activeRequestId = new AtomicLong(0L);
    private volatile boolean initialized = false;

    public LlmEngine(IGameEnvironment env, JavaLlamaServer aiService) {
        this.env = env;
        this.aiService = aiService;
    }

    public void initialize(String baseUrl) {
        this.initialized = aiService != null && aiService.isReady();
        if (this.initialized) {
            env.info("LLM engine initialized (direct library call mode)");
        } else {
            env.info("LLM engine initialized but service not ready yet");
        }
    }

    public long beginStreamRequest(Consumer<String> onError) {
        if (aiService == null) {
            env.error("LLM engine is not initialized: AI service is null", null);
            onError.accept("AI service is not available");
            return -1L;
        }

        if (!aiService.isReady()) {
            env.error("LLM engine is not ready", null);
            onError.accept("AI service is not ready");
            return -1L;
        }

        long requestId = activeRequestId.incrementAndGet();
        env.info("LLM request started, requestId=" + requestId);
        return requestId;
    }

    public void streamChatBlocking(long requestId, List<ChatMessage> messages, double temperature, boolean stream, boolean thinking, int maxTokens, LlmInvocationLane lane, boolean useRag, boolean useMemoryRag, int memoryRagTokenBudget, boolean includeRagHits, int taskPriority, boolean taskPreemptible, List<String> dynamicRag, LlmRagRoutingContext ragRouting, Consumer<String> onChunk, Consumer<LlmRagHit> onRagHit, Consumer<FinishReason> onFinish, Consumer<String> onError) {
        if (aiService == null || !aiService.isReady()) {
            onError.accept("AI service is not ready");
            onFinish.accept(FinishReason.FAILED);
            return;
        }

        InferenceLane effectiveLane = lane == LlmInvocationLane.TASK ? InferenceLane.TASK : InferenceLane.CHAT;
        FinishReason finishReason = FinishReason.FAILED;

        try {
            SamplerConfig sampler = createSampler(temperature);

            if (stream) {
                aiService.chatStream(toLibsMessages(messages), sampler, token -> {
                    if (requestId == activeRequestId.get()) {
                        onChunk.accept(token);
                    }
                });
                if (requestId == activeRequestId.get()) {
                    finishReason = FinishReason.COMPLETED;
                } else {
                    finishReason = FinishReason.CANCELLED;
                }
            } else {
                String response = aiService.chatSync(toLibsMessages(messages), sampler, maxTokens);
                if (requestId == activeRequestId.get() && response != null) {
                    onChunk.accept(response);
                    finishReason = FinishReason.COMPLETED;
                } else {
                    finishReason = FinishReason.CANCELLED;
                }
            }
        } catch (Exception e) {
            if (requestId != activeRequestId.get()) {
                finishReason = FinishReason.CANCELLED;
            } else {
                env.error("LLM inference failed", e);
                onError.accept("LLM inference error: " + e.getMessage());
                finishReason = FinishReason.FAILED;
            }
        } finally {
            onFinish.accept(finishReason);
        }
    }

    private SamplerConfig createSampler(double temperature) {
        SamplerConfig sampler = new SamplerConfig();
        sampler.setTemperature((float) normalizeTemperature(temperature));
        return sampler;
    }

    private List<ChatController.ChatMessage> toLibsMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(m -> new ChatController.ChatMessage(m.role(), m.content()))
                .toList();
    }

    private double normalizeTemperature(double temperature) {
        if (temperature < 0.0D || temperature > 2.0D || Double.isNaN(temperature) || Double.isInfinite(temperature)) {
            return 0.6D;
        }
        return temperature;
    }

    public void cancelGeneration() {
        activeRequestId.incrementAndGet();
        env.info("Cancelling LLM generation");
    }

    public void shutdown() {
        cancelGeneration();
        env.info("LLM engine closed");
    }

    public boolean isReady() {
        return aiService != null && aiService.isReady();
    }

    public boolean hasQueueCapacity() {
        return aiService != null && aiService.hasChatQueueCapacity();
    }
}