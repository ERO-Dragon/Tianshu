package com.rheinmetal.tianshu.function.weaving.service;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticEvent;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticPrivacy;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSeverity;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveQueryPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPrimitiveResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptResultPayload;
import com.rheinmetal.tianshu.function.weaving.contract.LLMRuntimeSnapshotPayload;
import com.rheinmetal.tianshu.function.weaving.protocol.LlmModuleId;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmContextBudget;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningProfile;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Generation, embedding and runtime facts for the cloud lane.
 *
 * <p>This service knows nothing about prompts beyond the message list it is handed, and nothing
 * about RAG: retrieval storage is a sibling service the protocol adapter talks to directly. What is
 * left is the part a transport really owns — send a request, report what came back, answer the three
 * measurement queries.</p>
 */
public final class LLMService {
    private final IGameEnvironment env;
    private final LlmInferenceClient inferenceClient;
    private final EmbeddingRuntime embeddingRuntime;
    private final LlmServiceMetadata metadata;
    private final LlmReasoningProfile reasoningProfile;
    private final String cacheNamespace;

    private LLMService(Builder builder) {
        this.env = Objects.requireNonNull(builder.env, "env");
        this.inferenceClient = Objects.requireNonNull(builder.inferenceClient, "inferenceClient");
        this.embeddingRuntime = Objects.requireNonNull(builder.embeddingRuntime, "embeddingRuntime");
        this.metadata = LlmServiceMetadata.from(builder.config);
        this.reasoningProfile = builder.reasoningProfile == null
                ? LlmReasoningProfile.NONE
                : builder.reasoningProfile;
        this.cacheNamespace = builder.cacheNamespace == null ? "" : builder.cacheNamespace.trim();
        env.info("[LLMService] Initialized, endpoint ready=" + inferenceClient.isReady());
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- generation ------------------------------------------------------------------------------

    public LLMResult chat(LlmPrompt prompt, Consumer<LlmPromptCacheUsage> cacheSink) {
        LlmPrompt effective = requirePrompt(prompt);
        try {
            LlmGenerationResult result = inferenceClient.chat(
                    effective.messages(),
                    effective.sampling(),
                    effective.maxTokens(),
                    effective.captureThinkingContent(),
                    effective.reasoningTier(),
                    cacheSink
            );
            LLMResult response = new LLMResult(
                    result == null ? "" : result.text(),
                    result == null ? "" : result.thinkingContent(),
                    toUsagePayload(result == null ? null : result.usage())
            );
            publishDialogueDiagnostic("CHAT_COMPLETED", effective, response.text());
            return response;
        } catch (CancellationException cancelled) {
            // Cancellation keeps its type: the caller decides, and the protocol face reports it as
            // CANCELLED rather than as a provider failure.
            throw cancelled;
        } catch (Exception exception) {
            env.error("[LLMService] Chat failed", exception);
            throw new RuntimeException("LLM chat failed: " + safeMessage(exception), exception);
        }
    }

    public LLMStreamResult chatStream(
            LlmPrompt prompt,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        LlmPrompt effective = requirePrompt(prompt);
        try {
            LlmStreamFinishHolder finishHolder = new LlmStreamFinishHolder();
            CompletableFuture<LlmGenerationResult> future = inferenceClient.chatStream(
                    effective.messages(),
                    effective.sampling(),
                    effective.maxTokens(),
                    effective.captureThinkingContent(),
                    effective.reasoningTier(),
                    safeTokenConsumer(onToken),
                    safeTokenConsumer(onThinking),
                    finishHolder::set,
                    cacheSink
            );
            LlmGenerationResult result = awaitCompletion(future);
            String text = result == null ? "" : result.text();
            publishDialogueDiagnostic("STREAM_COMPLETED", effective, text);
            return new LLMStreamResult(
                    text,
                    toStreamFinish(finishHolder.finish(), result == null ? null : result.thinkingContent())
            );
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (Exception exception) {
            env.error("[LLMService] Stream chat failed", exception);
            throw new RuntimeException("LLM stream chat failed: " + safeMessage(exception), exception);
        }
    }

    // --- embedding -------------------------------------------------------------------------------

    public float[] embed(String text) throws Exception {
        return embeddingRuntime.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        return embeddingRuntime.embed(texts);
    }

    public LLMPrimitiveResultPayload embedResponse(
            String requestId,
            List<String> texts,
            boolean includeVector,
            boolean includeEmbeddingDetails
    ) {
        if (!embeddingRuntime.available()) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED,
                    "LLM_EMBEDDING_UNAVAILABLE",
                    "The local embedding lane is not ready"
            );
        }
        try {
            List<LLMPrimitiveResultPayload.EmbedResultPayload> results = new ArrayList<>();
            if (texts != null && !texts.isEmpty()) {
                float[][] vectors = embed(texts);
                // The two flags answer different questions and are independent: "give me the vectors" and
                // "tell me which model and namespace produced them". ANDing them used to return no vector
                // at all to a caller that had only asked for vectors.
                String modelName = includeEmbeddingDetails ? metadata.embeddingModelName() : "";
                String namespace = includeEmbeddingDetails ? embeddingNamespace() : "";
                for (int index = 0; index < texts.size(); index++) {
                    float[] vector = vectors != null && index < vectors.length ? vectors[index] : null;
                    results.add(LLMPrimitiveResultPayload.EmbedResultPayload.of(
                            texts.get(index),
                            vector,
                            includeVector,
                            modelName,
                            namespace
                    ));
                }
            }
            return LLMPrimitiveResultPayload.embed(requestId, results);
        } catch (Exception exception) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_EMBED,
                    "LLM_EMBED_FAILED",
                    safeMessage(exception)
            );
        }
    }

    // --- measurement -----------------------------------------------------------------------------

    /**
     * Prompt-size estimate. The provider's own usage is authoritative for billing, but it only
     * exists after a request; a caller that budgets before sending has nothing else to ask.
     */
    public LLMPrimitiveResultPayload tokenCountResponse(String requestId, List<ChatMessage> messages) {
        try {
            return LLMPrimitiveResultPayload.tokenCount(
                    requestId,
                    inferenceClient.countChatPromptTokens(messages == null ? List.of() : messages)
            );
        } catch (Exception exception) {
            return LLMPrimitiveResultPayload.failed(
                    requestId,
                    LLMPrimitiveQueryPayload.QUERY_TYPE_TOKEN_COUNT,
                    "LLM_TOKEN_COUNT_FAILED",
                    safeMessage(exception)
            );
        }
    }

    public LLMPrimitiveResultPayload runtimeSnapshotResponse(String requestId) {
        return LLMPrimitiveResultPayload.runtime(requestId, runtimeSnapshot());
    }

    public LLMRuntimeSnapshotPayload runtimeSnapshot() {
        int window = metadata.declaredContextWindowTokens();
        return new LLMRuntimeSnapshotPayload(
                isReady(),
                embeddingRuntime.available(),
                embeddingRuntime.safeDimension(),
                window,
                LlmContextBudget.promptTokenBudget(window),
                metadata.embeddingModelName(),
                embeddingNamespace(),
                reasoningProfile.tierCount(),
                reasoningProfile.includesNoThinking(),
                reasoningProfile.defaultTier(),
                reasoningProfile.levels(),
                "",
                System.currentTimeMillis()
        );
    }

    public boolean isReady() {
        return inferenceClient.isReady();
    }

    // --- internals -------------------------------------------------------------------------------

    private LlmPrompt requirePrompt(LlmPrompt prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("LLM_EMPTY_REQUEST");
        }
        return prompt;
    }

    private String embeddingNamespace() {
        if (!cacheNamespace.isBlank()) {
            return cacheNamespace;
        }
        return stableSegment(metadata.embeddingModelName());
    }

    private static String stableSegment(String value) {
        String normalized = value == null || value.isBlank() ? "default" : value.trim();
        return Integer.toHexString(Objects.hash(normalized));
    }

    private void publishDialogueDiagnostic(String code, LlmPrompt prompt, String response) {
        String input = prompt == null ? "" : prompt.messages().stream()
                .filter(Objects::nonNull)
                .map(message -> (message.role == null ? "" : message.role) + ": "
                        + (message.content == null ? "" : message.content))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        env.diagnostics().publish(DiagnosticEvent.now(
                LlmModuleId.MODULE,
                code,
                DiagnosticSeverity.INFO,
                DiagnosticPrivacy.RAW_CONTENT,
                Map.of("input", input, "output", response == null ? "" : response)
        ));
    }

    private static Consumer<String> safeTokenConsumer(Consumer<String> consumer) {
        return token -> {
            if (consumer != null && token != null) {
                consumer.accept(token);
            }
        };
    }

    /**
     * Waits for one streamed answer and keeps the failure's type.
     *
     * <p>An interrupted caller cancels the future on the way out: the transport wires cancellation to
     * the response body, so the exchange stops instead of streaming tokens nobody will read until the
     * request timeout. The failure is unwrapped from {@link ExecutionException} for the same reason
     * {@code get()} is not enough — a cancellation has to arrive here as a cancellation.</p>
     */
    private static <T> T awaitCompletion(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("LLM_REQUEST_INTERRUPTED");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failed;
        }
    }

    private static LLMStreamFinish toStreamFinish(LlmStreamFinish finish, String fallbackThinking) {
        if (finish == null) {
            return new LLMStreamFinish(
                    "COMPLETED",
                    LLMPromptResultPayload.TokenUsagePayload.empty(),
                    null,
                    fallbackThinking == null ? "" : fallbackThinking
            );
        }
        return new LLMStreamFinish(
                finish.type() == null ? "COMPLETED" : finish.type().name(),
                toUsagePayload(finish.usage()),
                finish.error(),
                finish.thinkingContent()
        );
    }

    public static LLMPromptResultPayload.TokenUsagePayload toUsagePayload(LlmTokenUsage usage) {
        if (usage == null) {
            return LLMPromptResultPayload.TokenUsagePayload.empty();
        }
        return new LLMPromptResultPayload.TokenUsagePayload(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.thinkingTokens(),
                usage.outputTokens(),
                usage.totalTokens()
        );
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
    }

    public record LLMResult(
            String text,
            String thinkingContent,
            LLMPromptResultPayload.TokenUsagePayload usage
    ) {
        public LLMResult {
            text = text == null ? "" : text;
            thinkingContent = thinkingContent == null ? "" : thinkingContent;
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
        }
    }

    public record LLMStreamResult(String text, LLMStreamFinish finish) {
        public LLMStreamResult {
            text = text == null ? "" : text;
            finish = finish == null
                    ? LLMStreamFinish.completed(LLMPromptResultPayload.TokenUsagePayload.empty())
                    : finish;
        }
    }

    public record LLMStreamFinish(
            String type,
            LLMPromptResultPayload.TokenUsagePayload usage,
            Throwable error,
            String thinkingContent
    ) {
        public LLMStreamFinish(String type, LLMPromptResultPayload.TokenUsagePayload usage, Throwable error) {
            this(type, usage, error, "");
        }

        public LLMStreamFinish {
            type = type == null || type.isBlank() ? "COMPLETED" : type.trim().toUpperCase();
            usage = usage == null ? LLMPromptResultPayload.TokenUsagePayload.empty() : usage;
            thinkingContent = thinkingContent == null ? "" : thinkingContent;
        }

        static LLMStreamFinish completed(LLMPromptResultPayload.TokenUsagePayload usage) {
            return new LLMStreamFinish("COMPLETED", usage, null);
        }
    }

    private static final class LlmStreamFinishHolder {
        private volatile LlmStreamFinish finish;

        void set(LlmStreamFinish finish) {
            this.finish = finish;
        }

        LlmStreamFinish finish() {
            return finish;
        }
    }

    public static class Builder {
        private IGameEnvironment env;
        private WeavingConfiguration config;
        private LlmInferenceClient inferenceClient;
        private EmbeddingRuntime embeddingRuntime;
        private LlmReasoningProfile reasoningProfile = LlmReasoningProfile.NONE;
        private String cacheNamespace = "";

        public Builder env(IGameEnvironment env) {
            this.env = env;
            return this;
        }

        public Builder config(WeavingConfiguration config) {
            this.config = config;
            return this;
        }

        public Builder inferenceClient(LlmInferenceClient inferenceClient) {
            this.inferenceClient = inferenceClient;
            return this;
        }

        /** The live embedding lane; the same object RAG storage writes through. */
        public Builder embeddingRuntime(EmbeddingRuntime embeddingRuntime) {
            this.embeddingRuntime = embeddingRuntime;
            return this;
        }

        /** The selected provider's reasoning ladder; reported in the runtime snapshot. */
        public Builder reasoningProfile(LlmReasoningProfile reasoningProfile) {
            this.reasoningProfile = reasoningProfile == null ? LlmReasoningProfile.NONE : reasoningProfile;
            return this;
        }

        /** Namespace of the stored vectors; reported in the runtime snapshot. */
        public Builder cacheNamespace(String cacheNamespace) {
            this.cacheNamespace = cacheNamespace;
            return this;
        }

        public LLMService build() {
            Objects.requireNonNull(env, "env must be set");
            Objects.requireNonNull(inferenceClient, "inferenceClient must be set");
            Objects.requireNonNull(embeddingRuntime, "embeddingRuntime must be set");
            Objects.requireNonNull(config, "config must be set");
            return new LLMService(this);
        }
    }
}
