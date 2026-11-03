package com.rheinmetal.tianshu.function.weaving.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmTokenEstimator;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningWire;
import com.rheinmetal.tianshu.function.weaving.usage.LlmPromptCacheUsage;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmTokenUsage;
import com.rheinmetal.tianshu.libs.llm.StreamFinishType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Inference client for an OpenAI-compatible {@code POST {baseUrl}/chat/completions} endpoint.
 *
 * <p>Every request streams, so the protocol layer keeps receiving incremental tokens and text to
 * speech can start before the answer is complete; the blocking entry point assembles that same
 * stream into a whole result instead of issuing a second, non-streaming request.</p>
 *
 * <p>The API key is only ever written into the request's {@code Authorization} header. It is never
 * part of an exception message, a diagnostic message or {@link #toString()}, and provider error
 * bodies are scrubbed before they are reported because a gateway may echo the credential back.</p>
 */
public final class OpenAiCompatibleInferenceClient implements LlmInferenceClient {
    /** Timeout bounds: a shorter cap breaks long generations, a longer one can hang a session. */
    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String STREAM_MEDIA_TYPE = "text/event-stream";
    private static final String REDACTED = "<redacted>";
    private static final int MAX_ERROR_BODY_CHARS = 512;
    /** Below this prompt size the cache ratio is noise: the first turns of a session are all misses. */
    private static final int CACHE_RATIO_MIN_PROMPT_TOKENS = 1_024;
    /** A steady-state prompt should mostly hit the prefix cache; below this the prefix is drifting. */
    private static final double CACHE_RATIO_WARN_THRESHOLD = 0.3D;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final Consumer<String> diagnostics;
    private final HttpClient httpClient;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final LlmReasoningWire reasoningWire;
    private final Set<RemoteStream> activeStreams = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** The endpoint ships no tokenizer, so prompt size is estimated and calibrated from usage. */
    private final LlmTokenEstimator tokenEstimator = new LlmTokenEstimator();

    public OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, LlmReasoningWire.NONE);
    }

    /**
     * @param reasoningWire how this provider expresses reasoning depth; determined by the provider
     *                      profile, never guessed from the model id
     */
    public OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningWire reasoningWire
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningWire, null, null);
    }

    /** Transport seam: tests drive the real stream loop with a stub {@link HttpClient}, no socket. */
    OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            HttpClient httpClient,
            Executor executor
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, LlmReasoningWire.NONE, httpClient, executor);
    }

    OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningWire reasoningWire,
            HttpClient httpClient,
            Executor executor
    ) {
        this.endpoint = normalizeEndpoint(baseUrl);
        this.apiKey = requireHeaderSafe(apiKey == null ? "" : apiKey.trim());
        this.model = requireText(model, "model");
        this.timeout = clampTimeout(timeout);
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.reasoningWire = reasoningWire == null ? LlmReasoningWire.NONE : reasoningWire;
        this.httpClient = httpClient != null ? httpClient : newHttpClient(this.timeout);
        this.ownedExecutor = executor == null ? newOwnedExecutor() : null;
        this.executor = executor != null ? executor : ownedExecutor;
    }

    /** {@code baseUrl} plus the completions path, with trailing slashes collapsed. */
    public String endpoint() {
        return endpoint;
    }

    /** Aborts in-flight requests and releases the client's own worker threads. */
    public void shutdown() {
        closed.set(true);
        for (RemoteStream stream : activeStreams) {
            stream.cancel();
        }
        activeStreams.clear();
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    @Override
    public LlmGenerationResult chat(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            String reasoningEffort,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception {
        return await(startStream(
                messages, sampling, maxTokens, captureThinkingContent, reasoningEffort,
                null, null, null, cacheSink));
    }

    @Override
    public CompletableFuture<LlmGenerationResult> chatStream(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            String reasoningEffort,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        return startStream(
                messages, sampling, maxTokens, captureThinkingContent, reasoningEffort,
                onToken, onThinking, onFinish, cacheSink);
    }

    @Override
    public int countChatPromptTokens(List<ChatMessage> messages) {
        return tokenEstimator.estimate(messages);
    }

    @Override
    public boolean isReady() {
        return !closed.get() && !apiKey.isEmpty() && !model.isEmpty();
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleInferenceClient{endpoint=" + endpoint
                + ", model=" + model
                + ", timeoutSeconds=" + timeout.toSeconds() + "}";
    }

    // --- streaming -------------------------------------------------------------------------------

    private CompletableFuture<LlmGenerationResult> startStream(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            String reasoningEffort,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        CompletableFuture<LlmGenerationResult> future = new CompletableFuture<>();
        RemoteStream stream = new RemoteStream();
        activeStreams.add(stream);
        // Cancelling the returned future must abort the HTTP exchange instead of leaving a reader
        // thread blocked on a body nobody consumes any more.
        future.whenComplete((result, failure) -> {
            if (future.isCancelled() || failure instanceof CancellationException) {
                stream.cancel();
            }
        });
        Consumer<String> tokenSink = sink(onToken);
        Consumer<String> thinkingSink = captureThinkingContent ? sink(onThinking) : null;
        try {
            executor.execute(() -> {
                try {
                    LlmGenerationResult result = streamChat(
                            messages, sampling, maxTokens, reasoningEffort, tokenSink, thinkingSink, stream, cacheSink
                    );
                    notifyFinish(onFinish, result, null);
                    future.complete(result);
                } catch (CancellationException cancelled) {
                    notifyFinish(onFinish, null, cancelled);
                    future.completeExceptionally(cancelled);
                } catch (Throwable failure) {
                    Throwable reported = stream.cancelled()
                            ? new CancellationException("REMOTE_LLM_REQUEST_CANCELLED")
                            : failure;
                    report(reported);
                    notifyFinish(onFinish, null, reported);
                    future.completeExceptionally(reported);
                }
            });
        } catch (RuntimeException rejected) {
            activeStreams.remove(stream);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    private LlmGenerationResult streamChat(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            String reasoningEffort,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            RemoteStream stream,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("REMOTE_LLM_CLIENT_CLOSED");
        }
        try {
            HttpRequest request = buildRequest(buildChatRequestBody(
                    model, messages, sampling, maxTokens, reasoningWire, reasoningEffort));
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("REMOTE_LLM_REQUEST_INTERRUPTED");
            } catch (IOException failure) {
                throw new IllegalStateException("REMOTE_LLM_TRANSPORT_FAILED " + describe(failure), failure);
            }
            try (InputStream body = response.body()) {
                stream.attach(body);
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("REMOTE_LLM_HTTP_STATUS status=" + status + errorDetail(readErrorBody(body)));
                }
                LlmGenerationResult result = readStream(body, onToken, onThinking, stream, cacheSink);
                calibrateUsage(messages, result);
                return result;
            } catch (IOException failure) {
                if (stream.cancelled()) {
                    throw new CancellationException("REMOTE_LLM_REQUEST_CANCELLED");
                }
                throw new IllegalStateException("REMOTE_LLM_STREAM_FAILED " + describe(failure), failure);
            }
        } finally {
            stream.detach();
            activeStreams.remove(stream);
        }
    }

    private LlmGenerationResult readStream(
            InputStream body,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            RemoteStream stream,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws IOException {
        long deadline = System.nanoTime() + timeout.toNanos();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int promptTokens = 0;
        int completionTokens = 0;
        int cacheHitTokens = -1;
        int cacheMissTokens = -1;
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (stream.cancelled()) {
                throw new CancellationException("REMOTE_LLM_REQUEST_CANCELLED");
            }
            OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(line);
            if (event == null) {
                continue;
            }
            if (event.done()) {
                break;
            }
            if (event.promptTokens() != null) {
                promptTokens = event.promptTokens();
            }
            if (event.completionTokens() != null) {
                completionTokens = event.completionTokens();
            }
            if (event.cacheHitTokens() != null) {
                cacheHitTokens = event.cacheHitTokens();
            }
            if (event.cacheMissTokens() != null) {
                cacheMissTokens = event.cacheMissTokens();
            }
            if (!event.thinking().isEmpty()) {
                thinking.append(event.thinking());
                if (onThinking != null) {
                    onThinking.accept(event.thinking());
                }
            }
            if (!event.text().isEmpty()) {
                text.append(event.text());
                if (onToken != null) {
                    onToken.accept(event.text());
                }
            }
            // Guards providers that keep the socket open after the last token.
            if (System.nanoTime() - deadline > 0L) {
                throw new IllegalStateException("REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=" + timeout.toSeconds());
            }
        }
        reportCacheHitRatio(promptTokens, cacheHitTokens, cacheMissTokens);
        reportPromptCacheUsage(cacheSink, cacheHitTokens, cacheMissTokens);
        return new LlmGenerationResult(text.toString(), new LlmTokenUsage(promptTokens, completionTokens), thinking.toString());
    }

    // --- request construction --------------------------------------------------------------------

    /**
     * OpenAI-compatible chat-completions body: the four sampling fields, plus whatever reasoning
     * field this provider understands. An unknown or absent effort override leaves the endpoint's
     * default in place; it is never guessed from the model id.
     */
    static String buildChatRequestBody(
            String model,
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            LlmReasoningWire reasoningWire,
            String reasoningEffort
    ) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messageArray(messages));
        root.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);
        applySampling(root, sampling);
        applyReasoning(root, reasoningWire, reasoningEffort);
        if (maxTokens > 0) {
            root.addProperty("max_tokens", maxTokens);
        }
        return GSON.toJson(root);
    }

    private static JsonArray messageArray(List<ChatMessage> messages) {
        JsonArray array = new JsonArray();
        if (messages == null) {
            return array;
        }
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("role", normalizeRole(message.role));
            item.addProperty("content", message.content == null ? "" : message.content);
            array.add(item);
        }
        return array;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "user";
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "system", "assistant", "user" -> normalized;
            default -> "user";
        };
    }

    private static void applySampling(JsonObject root, LLMPromptRequestPayload.SamplingPayload sampling) {
        if (sampling == null) {
            return;
        }
        addClamped(root, "temperature", sampling.temperature(), 0.0F, 2.0F);
        addClamped(root, "top_p", sampling.topP(), 0.0F, 1.0F);
        addClamped(root, "frequency_penalty", sampling.frequencyPenalty(), -2.0F, 2.0F);
        addClamped(root, "presence_penalty", sampling.presencePenalty(), -2.0F, 2.0F);
    }

    /**
     * Translates the provider-neutral effort into this endpoint's fields. The mapping is deliberate:
     * DeepSeek's own table folds {@code medium} into {@code high}; an OpenAI-style endpoint has no way
     * to disable reasoning, so {@code off} becomes its lowest effort instead of being silently dropped.
     */
    private static void applyReasoning(JsonObject root, LlmReasoningWire wire, String effort) {
        if (wire == null || wire == LlmReasoningWire.NONE || effort == null || effort.isBlank()) {
            return;
        }
        switch (wire) {
            case DEEPSEEK_THINKING -> {
                JsonObject thinking = new JsonObject();
                boolean off = "off".equals(effort);
                thinking.addProperty("type", off ? "disabled" : "enabled");
                root.add("thinking", thinking);
                if (!off) {
                    root.addProperty("reasoning_effort", deepSeekEffort(effort));
                }
            }
            case OPENAI_EFFORT -> root.addProperty("reasoning_effort", openAiEffort(effort));
            default -> {
                // NONE handled above; no field to send.
            }
        }
    }

    private static String deepSeekEffort(String effort) {
        return switch (effort) {
            case "low" -> "low";
            case "max" -> "max";
            default -> "high";
        };
    }

    private static String openAiEffort(String effort) {
        return switch (effort) {
            case "medium" -> "medium";
            case "high", "max" -> "high";
            default -> "low";
        };
    }

    private static void addClamped(JsonObject root, String field, Float value, float min, float max) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return;
        }
        root.addProperty(field, Math.max(min, Math.min(max, value)));
    }

    private HttpRequest buildRequest(String body) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", STREAM_MEDIA_TYPE)
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    // --- lifecycle helpers ------------------------------------------------------------------------

    private static HttpClient newHttpClient(Duration timeout) {
        Duration connectTimeout = timeout.compareTo(CONNECT_TIMEOUT) < 0 ? timeout : CONNECT_TIMEOUT;
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static ExecutorService newOwnedExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "tianshu-llm-remote");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(factory);
    }

    private static Duration clampTimeout(Duration timeout) {
        Duration effective = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        if (effective.compareTo(MIN_TIMEOUT) < 0) {
            return MIN_TIMEOUT;
        }
        return effective.compareTo(MAX_TIMEOUT) > 0 ? MAX_TIMEOUT : effective;
    }

    private static String normalizeEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new IllegalArgumentException("REMOTE_LLM_BASE_URL_MUST_BE_HTTP: " + normalized);
        }
        return normalized.endsWith(COMPLETIONS_PATH) ? normalized : normalized + COMPLETIONS_PATH;
    }

    private static String requireText(String value, String field) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("REMOTE_LLM_MISSING_" + field.toUpperCase(Locale.ROOT));
        }
        return trimmed;
    }

    /**
     * The JDK rejects a header value with control or non-ASCII characters, and its exception quotes
     * the value it rejected. Validating here keeps the credential out of that message.
     */
    private static String requireHeaderSafe(String apiKey) {
        for (int index = 0; index < apiKey.length(); index++) {
            char character = apiKey.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                throw new IllegalArgumentException("REMOTE_LLM_API_KEY_CONTAINS_INVALID_CHARACTERS");
            }
        }
        return apiKey;
    }

    private static Consumer<String> sink(Consumer<String> consumer) {
        if (consumer == null) {
            return null;
        }
        return token -> {
            if (token != null && !token.isEmpty()) {
                consumer.accept(token);
            }
        };
    }

    // --- provider reporting ---------------------------------------------------------------------

    /**
     * Surfaces prefix-cache health. The provider reports how much of the prompt it served from its
     * cache; a low ratio on a non-trivial prompt means our prefix stopped being byte-stable, which is
     * the single most expensive regression this prompt layout can suffer.
     */
    private void reportCacheHitRatio(int promptTokens, int cacheHitTokens, int cacheMissTokens) {
        if (cacheHitTokens < 0 || cacheMissTokens < 0 || promptTokens < CACHE_RATIO_MIN_PROMPT_TOKENS) {
            return;
        }
        int total = cacheHitTokens + cacheMissTokens;
        if (total <= 0) {
            return;
        }
        double ratio = (double) cacheHitTokens / (double) total;
        if (ratio >= CACHE_RATIO_WARN_THRESHOLD) {
            return;
        }
        diagnostics.accept("[LLM] Prefix cache hit ratio is low: ratio=" + String.format(Locale.ROOT, "%.2f", ratio)
                + " hit=" + cacheHitTokens + " miss=" + cacheMissTokens + " prompt=" + promptTokens
                + " — the prompt prefix is drifting, so the cache never warms up.");
    }

    /**
     * Hands the provider's prompt-cache split to the caller's accounting hook. This is the only place
     * the split exists: the token counters travel with the result, but the hit/miss breakdown does
     * not, so an observer has to be offered it here or it is lost.
     */
    private static void reportPromptCacheUsage(
            Consumer<LlmPromptCacheUsage> cacheSink,
            int cacheHitTokens,
            int cacheMissTokens
    ) {
        if (cacheSink == null || cacheHitTokens < 0 || cacheMissTokens < 0) {
            return;
        }
        try {
            cacheSink.accept(new LlmPromptCacheUsage(cacheHitTokens, cacheMissTokens));
        } catch (RuntimeException ignored) {
            // Accounting is strictly an observer of a request that already finished successfully.
        }
    }

    /**
     * Feeds the provider's reported prompt size back into the estimator. The endpoint ships no
     * tokenizer, so this is the only way the estimate converges on the real number.
     */
    private void calibrateUsage(List<ChatMessage> messages, LlmGenerationResult result) {
        if (result == null || result.usage() == null || messages == null) {
            return;
        }
        int actual = result.usage().promptTokens();
        if (actual <= 0) {
            return;
        }
        tokenEstimator.calibrate(tokenEstimator.estimate(messages), actual);
    }

    private void report(Throwable failure) {
        if (failure instanceof CancellationException) {
            return;
        }
        diagnostics.accept(scrub("REMOTE_LLM_REQUEST_FAILED " + describe(failure)));
    }

    /** Strips the credential from any text that leaves this client. */
    private String scrub(String message) {
        if (message == null) {
            return "";
        }
        return apiKey.isEmpty() ? message : message.replace(apiKey, REDACTED);
    }

    private static String describe(Throwable failure) {
        String message = failure == null ? "" : failure.getMessage();
        String type = failure == null ? "failure" : failure.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private String errorDetail(String errorBody) {
        if (errorBody == null || errorBody.isBlank()) {
            return "";
        }
        String scrubbed = scrub(errorBody.trim());
        String bounded = scrubbed.length() > MAX_ERROR_BODY_CHARS
                ? scrubbed.substring(0, MAX_ERROR_BODY_CHARS) + "..."
                : scrubbed;
        return " body=" + bounded;
    }

    private static String readErrorBody(InputStream body) {
        try {
            byte[] buffer = new byte[MAX_ERROR_BODY_CHARS];
            int read = body.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "";
        }
    }

    private static void notifyFinish(Consumer<LlmStreamFinish> onFinish, LlmGenerationResult result, Throwable failure) {
        if (onFinish == null) {
            return;
        }
        LlmStreamFinish finish = failure == null
                ? new LlmStreamFinish(StreamFinishType.COMPLETED, result.usage(), null, result.thinkingContent())
                : new LlmStreamFinish(
                failure instanceof CancellationException ? StreamFinishType.CANCELLED : StreamFinishType.FAILED,
                new LlmTokenUsage(0, 0),
                failure);
        try {
            onFinish.accept(finish);
        } catch (RuntimeException ignored) {
            // A broken observer must not fail a generation that already streamed its tokens.
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        try {
            return future.get();
        } catch (CancellationException cancelled) {
            throw cancelled;
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

    /** Handle of one in-flight request: cancelling it closes the response stream and aborts the exchange. */
    private static final class RemoteStream {
        private volatile boolean cancelled;
        private volatile InputStream stream;

        void attach(InputStream body) {
            this.stream = body;
            if (cancelled) {
                close(body);
            }
        }

        void detach() {
            this.stream = null;
        }

        void cancel() {
            cancelled = true;
            close(stream);
        }

        boolean cancelled() {
            return cancelled;
        }

        private static void close(InputStream body) {
            if (body == null) {
                return;
            }
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing an already aborted stream is expected; the reader observes the failure.
            }
        }
    }
}
