package com.rheinmetal.tianshu.function.weaving.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.function.weaving.contract.LLMPromptRequestPayload;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmHttpTransport;
import com.rheinmetal.tianshu.function.weaving.runtime.LlmTokenEstimator;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningProfile;
import com.rheinmetal.tianshu.function.weaving.settings.LlmReasoningWire;
import com.rheinmetal.tianshu.function.weaving.settings.LlmWireCompat;
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
    /** Arms the deadline of one request; null when the caller has no scheduler to offer. */
    private final LlmTimeoutScheduler timeoutScheduler;
    private final LlmReasoningProfile reasoningProfile;
    /** Optional request fields this endpoint accepts; {@link LlmWireCompat#DEFAULT} is the OpenAI shape. */
    private final LlmWireCompat wireCompat;
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
        this(baseUrl, apiKey, model, timeout, diagnostics, LlmReasoningProfile.NONE);
    }

    /**
     * @param reasoningProfile the provider's ordered reasoning ladder; determined by the provider
     *                         profile, never guessed from the model id
     */
    public OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningProfile, null, null, null);
    }

    /**
     * @param timeoutScheduler arms the per-request deadline that aborts a body which stopped sending;
     *                         {@code null} leaves the request bounded only by the connection itself,
     *                         which is what tests and headless callers want
     */
    public OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile,
            LlmTimeoutScheduler timeoutScheduler
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningProfile, null, null, timeoutScheduler,
                LlmWireCompat.DEFAULT);
    }

    /**
     * @param wireCompat the two optional request fields this endpoint accepts; {@code null} means the
     *                   strict OpenAI shape
     */
    public OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile,
            LlmTimeoutScheduler timeoutScheduler,
            LlmWireCompat wireCompat
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningProfile, null, null, timeoutScheduler, wireCompat);
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
        this(baseUrl, apiKey, model, timeout, diagnostics, LlmReasoningProfile.NONE, httpClient, executor, null);
    }

    OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile,
            HttpClient httpClient,
            Executor executor
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningProfile, httpClient, executor, null);
    }

    OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile,
            HttpClient httpClient,
            Executor executor,
            LlmTimeoutScheduler timeoutScheduler
    ) {
        this(baseUrl, apiKey, model, timeout, diagnostics, reasoningProfile, httpClient, executor, timeoutScheduler,
                LlmWireCompat.DEFAULT);
    }

    OpenAiCompatibleInferenceClient(
            String baseUrl,
            String apiKey,
            String model,
            Duration timeout,
            Consumer<String> diagnostics,
            LlmReasoningProfile reasoningProfile,
            HttpClient httpClient,
            Executor executor,
            LlmTimeoutScheduler timeoutScheduler,
            LlmWireCompat wireCompat
    ) {
        this.endpoint = normalizeEndpoint(baseUrl);
        this.apiKey = requireHeaderSafe(apiKey == null ? "" : apiKey.trim());
        this.model = requireText(model, "model");
        this.timeout = clampTimeout(timeout);
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.reasoningProfile = reasoningProfile == null ? LlmReasoningProfile.NONE : reasoningProfile;
        this.wireCompat = wireCompat == null ? LlmWireCompat.DEFAULT : wireCompat;
        this.httpClient = httpClient != null ? httpClient : LlmHttpTransport.client();
        this.timeoutScheduler = timeoutScheduler;
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
            Integer reasoningTier,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) throws Exception {
        return await(startStream(
                messages, sampling, maxTokens, captureThinkingContent, reasoningTier,
                null, null, null, cacheSink));
    }

    @Override
    public CompletableFuture<LlmGenerationResult> chatStream(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            boolean captureThinkingContent,
            Integer reasoningTier,
            Consumer<String> onToken,
            Consumer<String> onThinking,
            Consumer<LlmStreamFinish> onFinish,
            Consumer<LlmPromptCacheUsage> cacheSink
    ) {
        return startStream(
                messages, sampling, maxTokens, captureThinkingContent, reasoningTier,
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
            Integer reasoningTier,
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
        // Armed before the exchange starts: the request timeout covers the wait for a response head, not a
        // body that stops mid-stream, so without this a stalled provider holds the reader and its socket.
        LlmTimeoutScheduler.Handle deadline = armDeadline(stream);
        try {
            executor.execute(() -> {
                try {
                    LlmGenerationResult result = streamChat(
                            messages, sampling, maxTokens, reasoningTier, tokenSink, thinkingSink, stream, cacheSink
                    );
                    notifyFinish(onFinish, result, null);
                    future.complete(result);
                } catch (CancellationException cancelled) {
                    notifyFinish(onFinish, null, cancelled);
                    future.completeExceptionally(cancelled);
                } catch (Throwable failure) {
                    Throwable reported = stream.timedOut()
                            ? streamStopped(stream)
                            : stream.cancelled()
                            ? new CancellationException("REMOTE_LLM_REQUEST_CANCELLED")
                            : failure;
                    report(reported);
                    notifyFinish(onFinish, null, reported);
                    future.completeExceptionally(reported);
                } finally {
                    disarm(deadline);
                }
            });
        } catch (RuntimeException rejected) {
            disarm(deadline);
            activeStreams.remove(stream);
            future.completeExceptionally(rejected);
        }
        return future;
    }

    /**
     * Arms the request deadline, or returns {@code null} when no scheduler is wired.
     *
     * <p>A scheduler that refuses the timer is reported and then ignored: the request is still bounded by
     * the connection, and failing it here would turn a missing watchdog into a missing answer.</p>
     */
    private LlmTimeoutScheduler.Handle armDeadline(RemoteStream stream) {
        if (timeoutScheduler == null) {
            return null;
        }
        try {
            return timeoutScheduler.schedule(timeout, stream::timeout);
        } catch (RuntimeException unavailable) {
            report(new IllegalStateException("REMOTE_LLM_DEADLINE_UNAVAILABLE " + describe(unavailable), unavailable));
            return null;
        }
    }

    private static void disarm(LlmTimeoutScheduler.Handle handle) {
        if (handle != null) {
            handle.cancel();
        }
    }

    private LlmGenerationResult streamChat(
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            Integer reasoningTier,
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
                    model, messages, sampling, maxTokens, reasoningProfile, reasoningTier, wireCompat));
            HttpResponse<InputStream> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("REMOTE_LLM_REQUEST_INTERRUPTED");
            } catch (IOException failure) {
                if (stream.timedOut()) {
                    throw streamStopped(stream);
                }
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
                if (stream.timedOut() || stream.cancelled()) {
                    throw streamStopped(stream);
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
        String finishReason = "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (stream.cancelled()) {
                throw streamStopped(stream);
            }
            OpenAiChatStreamParser.Event event = OpenAiChatStreamParser.parse(line);
            if (event == null) {
                continue;
            }
            if (event.done()) {
                break;
            }
            if (!event.finishReason().isEmpty()) {
                finishReason = event.finishReason();
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
        if ("content_filter".equalsIgnoreCase(finishReason)) {
            // The provider answered, but the answer was cut by its safety filter. Reporting COMPLETED
            // here would hand an empty or partial reply to the caller as if it were a full one.
            throw new IllegalStateException("REMOTE_LLM_CONTENT_FILTERED");
        }
        reportCacheHitRatio(promptTokens, cacheHitTokens, cacheMissTokens);
        reportPromptCacheUsage(cacheSink, cacheHitTokens, cacheMissTokens);
        return new LlmGenerationResult(text.toString(), new LlmTokenUsage(promptTokens, completionTokens), thinking.toString());
    }

    // --- request construction --------------------------------------------------------------------

    /**
     * OpenAI-compatible chat-completions body: the four sampling fields, plus the reasoning field
     * this provider understands. The tier is an index into the profile's ladder; the name sent on the
     * wire is the provider's own level name, never an alias.
     *
     * <p>The two optional fields follow {@link LlmWireCompat}: a gateway that predates
     * {@code stream_options} or rejects {@code max_tokens} says so on its own entry, instead of the
     * request always assuming OpenAI's current shape.</p>
     */
    static String buildChatRequestBody(
            String model,
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            LlmReasoningProfile reasoningProfile,
            Integer reasoningTier
    ) {
        return buildChatRequestBody(model, messages, sampling, maxTokens, reasoningProfile, reasoningTier,
                LlmWireCompat.DEFAULT);
    }

    static String buildChatRequestBody(
            String model,
            List<ChatMessage> messages,
            LLMPromptRequestPayload.SamplingPayload sampling,
            int maxTokens,
            LlmReasoningProfile reasoningProfile,
            Integer reasoningTier,
            LlmWireCompat wireCompat
    ) {
        LlmWireCompat effective = wireCompat == null ? LlmWireCompat.DEFAULT : wireCompat;
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.add("messages", messageArray(messages));
        root.addProperty("stream", true);
        if (effective.includeStreamUsage()) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }
        applySampling(root, sampling);
        applyReasoning(root, reasoningProfile, reasoningTier);
        if (maxTokens > 0) {
            root.addProperty(effective.maxTokensField().wireName(), maxTokens);
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
     * Encodes the selected tier with the profile's wire shape. {@code tier 0} is the non-thinking
     * slot when the profile has one; every other tier sends the provider's own level name. A missing
     * tier uses the model's default tier, so "no override" still means the documented default
     * (usually low) rather than whatever the endpoint does by itself. A profile without a ladder
     * sends nothing.
     */
    private static void applyReasoning(JsonObject root, LlmReasoningProfile profile, Integer tier) {
        if (profile == null || !profile.hasControl()) {
            return;
        }
        int applied = tier == null ? profile.defaultTier() : profile.clampTier(tier);
        String name = profile.levelName(applied);
        switch (profile.wire()) {
            case DEEPSEEK_THINKING -> {
                JsonObject thinking = new JsonObject();
                thinking.addProperty("type", name == null ? "disabled" : "enabled");
                root.add("thinking", thinking);
                if (name != null) {
                    root.addProperty("reasoning_effort", name);
                }
            }
            case OPENAI_EFFORT -> {
                if (name != null) {
                    root.addProperty("reasoning_effort", name);
                }
            }
            default -> {
                // NONE: no reasoning field exists.
            }
        }
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
        } catch (InterruptedException interrupted) {
            // Cancelling the future closes the response body, so the reader thread stops instead of
            // streaming an answer the caller already gave up on.
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("REMOTE_LLM_REQUEST_INTERRUPTED");
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
        /** Set when the request deadline fired, so the failure is reported as a timeout, not a cancel. */
        private volatile boolean timedOut;
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

        /** The request ran past its deadline: abort the exchange and remember why. */
        void timeout() {
            timedOut = true;
            cancel();
        }

        void cancel() {
            cancelled = true;
            close(stream);
        }

        boolean cancelled() {
            return cancelled;
        }

        boolean timedOut() {
            return timedOut;
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

    /**
     * The failure of an aborted stream: a timeout when the deadline fired, a cancellation otherwise.
     *
     * <p>The distinction matters to the caller: a timeout is a failed request, a cancellation is one the
     * caller gave up on, and they are reported under different protocol codes.</p>
     */
    private RuntimeException streamStopped(RemoteStream stream) {
        if (stream.timedOut()) {
            return new IllegalStateException(
                    "REMOTE_LLM_STREAM_TIMEOUT timeoutSeconds=" + timeout.toSeconds());
        }
        return new CancellationException("REMOTE_LLM_REQUEST_CANCELLED");
    }
}
