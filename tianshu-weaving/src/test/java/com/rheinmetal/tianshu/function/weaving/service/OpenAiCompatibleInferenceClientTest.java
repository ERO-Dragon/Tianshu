package com.rheinmetal.tianshu.function.weaving.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the cloud client through a stubbed transport. No test opens a socket: the stream loop,
 * the SSE decoding, the HTTP failure reporting and the cancellation path all run against an
 * in-memory {@link InputStream}.
 */
class OpenAiCompatibleInferenceClientTest {
    private static final String BASE_URL = "https://api.deepseek.com/v1";
    /** The production default, so this fixture cannot drift away from what a fresh install sends. */
    private static final String MODEL = WeavingConfiguration.DEFAULT_LLM_REMOTE_MODEL;
    private static final Duration AWAIT = Duration.ofSeconds(5);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tianshu-llm-remote-test");
        thread.setDaemon(true);
        return thread;
    });

    @AfterAll
    static void shutDownExecutor() {
        EXECUTOR.shutdownNow();
    }

    @Test
    void buildsOpenAiCompatibleStreamingBody() {
        SamplerConfig sampler = SamplerConfig.defaults();
        sampler.setTemperature(0.3F);
        sampler.setTopP(0.9F);

        String body = OpenAiCompatibleInferenceClient.buildChatRequestBody(
                MODEL,
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi"), ChatMessage.assistant("hello")),
                sampler,
                128
        );
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        assertEquals(MODEL, root.get("model").getAsString());
        assertTrue(root.get("stream").getAsBoolean());
        assertTrue(root.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        assertEquals(128, root.get("max_tokens").getAsInt());
        assertEquals(0.3F, root.get("temperature").getAsFloat());
        assertEquals(0.9F, root.get("top_p").getAsFloat());

        JsonArray messages = root.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        assertEquals("system", role(messages, 0));
        assertEquals("sys", content(messages, 0));
        assertEquals("user", role(messages, 1));
        assertEquals("hi", content(messages, 1));
        assertEquals("assistant", role(messages, 2));
        assertEquals("hello", content(messages, 2));
    }

    @Test
    void normalizesBaseUrlBeforeAppendingTheCompletionsPath() {
        assertEquals(
                BASE_URL + "/chat/completions",
                client(stub(200), "sk-test", BASE_URL).endpoint()
        );
        assertEquals(
                BASE_URL + "/chat/completions",
                client(stub(200), "sk-test", BASE_URL + "/").endpoint()
        );
        assertEquals(
                BASE_URL + "/chat/completions",
                client(stub(200), "sk-test", BASE_URL + "///").endpoint()
        );
        assertEquals(
                BASE_URL + "/chat/completions",
                client(stub(200), "sk-test", BASE_URL + "/chat/completions").endpoint()
        );
    }

    @Test
    void streamsIncrementalTokensAndSkipsUnusableFrames() throws Exception {
        StubHttpClient transport = stub(200,
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}",
                "",
                ": keep-alive",
                "event: message",
                "data: {broken",
                "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}",
                "data: [DONE]",
                "data: {\"choices\":[{\"delta\":{\"content\":\"after-done\"}}]}"
        );
        List<String> diagnostics = new ArrayList<>();
        OpenAiCompatibleInferenceClient client = client(transport, "sk-test", diagnostics::add);
        List<String> tokens = new ArrayList<>();

        CompletableFuture<String> streamed = client.chatStreamWithUsage(
                messages(), SamplerConfig.defaults(), 0, null, tokens::add, null);

        assertEquals("Hello", streamed.get(AWAIT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(List.of("Hel", "lo"), tokens);
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void assemblesWholeResultFromTheSameStreamForBlockingCallers() throws Exception {
        String[] lines = {
                "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}",
                "data: [DONE]"
        };
        OpenAiCompatibleInferenceClient client = client(stub(200, lines), "sk-test");

        assertEquals("answer", client.chat(messages(), SamplerConfig.defaults(), 64));
        assertEquals("answer", client.chatWithUsage(messages(), SamplerConfig.defaults(), 64, null).text());
    }

    @Test
    void httpFailureNeverEchoesTheApiKey() {
        String apiKey = "sk-live-secret-value";
        StubHttpClient transport = stub(
                401,
                "{\"error\":\"invalid credential: Bearer " + apiKey + "\"}"
        );
        List<String> diagnostics = new ArrayList<>();
        OpenAiCompatibleInferenceClient client = client(transport, apiKey, diagnostics::add);

        Exception failure = assertThrows(
                Exception.class,
                () -> client.chat(messages(), SamplerConfig.defaults(), 0)
        );

        assertTrue(failure.getMessage().contains("status=401"), failure.getMessage());
        assertFalse(failure.getMessage().contains(apiKey));
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertFalse(diagnostics.get(0).contains(apiKey), diagnostics.get(0));
    }

    @Test
    void stringifiedClientNeverContainsTheApiKey() {
        String apiKey = "sk-live-secret-value";
        OpenAiCompatibleInferenceClient client = client(stub(200), apiKey);

        assertFalse(client.toString().contains(apiKey));
        assertTrue(client.toString().contains(MODEL));
    }

    @Test
    void cancellingTheFutureClosesTheActiveResponseStream() throws Exception {
        BlockingSseStream blocking = new BlockingSseStream();
        List<String> diagnostics = new ArrayList<>();
        OpenAiCompatibleInferenceClient client = client(stub(200, () -> blocking), "sk-test", diagnostics::add);

        CompletableFuture<String> streamed = client.chatStreamWithUsage(
                messages(), SamplerConfig.defaults(), 0, null, token -> { }, null);
        assertTrue(blocking.awaitReading(AWAIT), "the reader thread must start consuming the stream");

        assertTrue(streamed.cancel(true));
        assertTrue(blocking.awaitClosed(AWAIT), "cancelling must abort the response stream");
        assertTrue(streamed.isCancelled());
        assertTrue(diagnostics.isEmpty(), diagnostics.toString());
    }

    @Test
    void shutdownClosesInFlightStreams() throws Exception {
        BlockingSseStream blocking = new BlockingSseStream();
        OpenAiCompatibleInferenceClient client = client(stub(200, () -> blocking), "sk-test");

        client.chatStreamWithUsage(messages(), SamplerConfig.defaults(), 0, null, token -> { }, null);
        assertTrue(blocking.awaitReading(AWAIT), "the reader thread must start consuming the stream");

        client.shutdown();

        assertTrue(blocking.awaitClosed(AWAIT), "shutdown must abort the response stream");
        assertFalse(client.isReady());
    }

    // --- fixtures -----------------------------------------------------------------------------

    private static OpenAiCompatibleInferenceClient client(StubHttpClient transport, String apiKey) {
        return client(transport, apiKey, message -> { }, BASE_URL);
    }

    private static OpenAiCompatibleInferenceClient client(
            StubHttpClient transport,
            String apiKey,
            Consumer<String> diagnostics
    ) {
        return client(transport, apiKey, diagnostics, BASE_URL);
    }

    private static OpenAiCompatibleInferenceClient client(StubHttpClient transport, String apiKey, String baseUrl) {
        return client(transport, apiKey, message -> { }, baseUrl);
    }

    private static OpenAiCompatibleInferenceClient client(
            StubHttpClient transport,
            String apiKey,
            Consumer<String> diagnostics,
            String baseUrl
    ) {
        return new OpenAiCompatibleInferenceClient(
                baseUrl,
                apiKey,
                MODEL,
                Duration.ofSeconds(30),
                diagnostics,
                transport,
                EXECUTOR
        );
    }

    private static StubHttpClient stub(int status, String... lines) {
        return new StubHttpClient(status, () -> sse(lines));
    }

    private static StubHttpClient stub(int status, Supplier<InputStream> body) {
        return new StubHttpClient(status, body);
    }

    private static List<ChatMessage> messages() {
        return List.of(ChatMessage.system("sys"), ChatMessage.user("hi"));
    }

    private static String role(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("role").getAsString();
    }

    private static String content(JsonArray messages, int index) {
        return messages.get(index).getAsJsonObject().get("content").getAsString();
    }

    private static InputStream sse(String... lines) {
        return new ByteArrayInputStream((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static final class BlockingSseStream extends InputStream {
        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            reading.countDown();
            try {
                closed.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
            throw new IOException("REMOTE_STREAM_CLOSED");
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean awaitReading(Duration timeout) throws InterruptedException {
            return reading.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean awaitClosed(Duration timeout) throws InterruptedException {
            return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static final class StubHttpClient extends HttpClient {
        private final int status;
        private final Supplier<InputStream> body;

        StubHttpClient(int status, Supplier<InputStream> body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(5));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) new StubResponse(status, request, body.get());
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException("the client under test sends synchronously");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException("the client under test sends synchronously");
        }
    }

    private static final class StubResponse implements HttpResponse<InputStream> {
        private final int status;
        private final HttpRequest request;
        private final InputStream body;

        StubResponse(int status, HttpRequest request, InputStream body) {
            this.status = status;
            this.request = request;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("text/event-stream")), (name, value) -> true);
        }

        @Override
        public InputStream body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
