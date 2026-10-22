package com.rheinmetal.tianshu.function.weaving.catalog;

import com.rheinmetal.tianshu.function.weaving.LlmModuleService;
import com.rheinmetal.tianshu.function.weaving.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.weaving.usage.LlmUsageLedger;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider model-list seam: what the documented OpenAI-compatible answer parses to, what every
 * failure reports, and the one guarantee the settings page depends on — exactly one answer per request,
 * whatever the transport does.
 */
class LlmModelCatalogTest {
    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String API_KEY = "sk-catalog-secret";
    private static final String OPENAI_LIST_BODY = """
            {"object":"list","data":[
              {"id":"deepseek-flash","object":"model","owned_by":"deepseek"},
              {"id":"deepseek-reasoner","object":"model","owned_by":"deepseek"}
            ]}""";

    @Test
    void readsTheDocumentedOpenAiList() {
        Optional<List<String>> ids = LlmModelCatalogParser.parseModelIds(OPENAI_LIST_BODY);

        assertTrue(ids.isPresent());
        assertEquals(List.of("deepseek-flash", "deepseek-reasoner"), ids.get());
    }

    @Test
    void readsABareArrayOfIdsAsWell() {
        Optional<List<String>> ids = LlmModelCatalogParser.parseModelIds(
                "[{\"id\":\"local-model\"},{\"id\":\"other-model\"}]");

        assertTrue(ids.isPresent());
        assertEquals(List.of("local-model", "other-model"), ids.get());
    }

    @Test
    void anEmptyDataArrayIsAValidAnswerWithoutModels() {
        Optional<List<String>> ids = LlmModelCatalogParser.parseModelIds("{\"object\":\"list\",\"data\":[]}");

        assertTrue(ids.isPresent());
        assertTrue(ids.get().isEmpty());
    }

    @Test
    void aBodyThatIsNotAModelListIsUnreadable() {
        assertTrue(LlmModelCatalogParser.parseModelIds("not json").isEmpty());
        assertTrue(LlmModelCatalogParser.parseModelIds("").isEmpty());
        assertTrue(LlmModelCatalogParser.parseModelIds(null).isEmpty());
        assertTrue(LlmModelCatalogParser.parseModelIds("{\"error\":{\"message\":\"bad key\"}}").isEmpty());
        assertTrue(LlmModelCatalogParser.parseModelIds("{\"data\":[{\"name\":\"no id\"}]}").isEmpty());
        assertTrue(LlmModelCatalogParser.parseModelIds("{\"data\":[\"deepseek-flash\"]}").isEmpty());
    }

    @Test
    void fetchAsksTheModelsPathWithTheCredentialAndReturnsTheIds() throws Exception {
        StubHttpClient http = new StubHttpClient(200, OPENAI_LIST_BODY);
        LlmModelCatalogResult result = fetch(http);

        assertTrue(result.success(), result::toString);
        assertEquals(List.of("deepseek-flash", "deepseek-reasoner"), result.modelIds());
        HttpRequest request = http.requests().get(0);
        assertEquals(BASE_URL + "/models", request.uri().toString());
        assertEquals("GET", request.method());
        assertEquals(Optional.of("Bearer " + API_KEY), request.headers().firstValue("Authorization"));
        assertEquals("", result.messageKey(), "a successful answer has no message to show");
    }

    @Test
    void aTrailingSlashDoesNotDoubleTheModelsPath() {
        assertEquals(BASE_URL + "/models", OpenAiCompatibleModelCatalogClient.modelsEndpoint(BASE_URL + "/"));
        assertEquals("", OpenAiCompatibleModelCatalogClient.modelsEndpoint("api.deepseek.com/v1"));
        assertEquals("", OpenAiCompatibleModelCatalogClient.modelsEndpoint(""));
        assertEquals("", OpenAiCompatibleModelCatalogClient.modelsEndpoint(null));
    }

    @Test
    void anEmptyListIsReportedAsEmptyInsteadOfSuccess() throws Exception {
        LlmModelCatalogResult result = fetch(new StubHttpClient(200, "{\"object\":\"list\",\"data\":[]}"));

        assertEquals(LlmModelCatalogState.EMPTY, result.state());
        assertEquals("tianshu.gui.llm.catalog.empty", result.messageKey());
        assertTrue(result.modelIds().isEmpty());
        assertFalse(result.success());
    }

    @Test
    void aRejectedCredentialIsReportedAsUnauthorized() throws Exception {
        LlmModelCatalogResult result = fetch(new StubHttpClient(401, "{\"error\":\"invalid key\"}"));

        assertEquals(LlmModelCatalogState.UNAUTHORIZED, result.state());
        assertEquals("tianshu.gui.llm.catalog.unauthorized", result.messageKey());
    }

    @Test
    void anyOtherStatusCarriesTheStatusCode() throws Exception {
        LlmModelCatalogResult result = fetch(new StubHttpClient(503, ""));

        assertEquals(LlmModelCatalogState.HTTP_ERROR, result.state());
        assertEquals("503", result.detail());
        assertEquals("tianshu.gui.llm.catalog.http_error", result.messageKey());
    }

    @Test
    void anUnreadableBodyKeepsNothingAndSaysSo() throws Exception {
        LlmModelCatalogResult result = fetch(new StubHttpClient(200, "<html>gateway</html>"));

        assertEquals(LlmModelCatalogState.MALFORMED_RESPONSE, result.state());
        assertEquals("tianshu.gui.llm.catalog.malformed", result.messageKey());
        assertTrue(result.modelIds().isEmpty());
    }

    @Test
    void aBlankCredentialIsStillSentSoTheProviderDecides() {
        StubHttpClient http = new StubHttpClient(200, OPENAI_LIST_BODY);
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(message -> { }, http, Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels(BASE_URL, "  ", LlmModelCatalogKeySource.NONE, sink);

        // The page fetches before anything is saved, so a blank key must reach the provider instead of
        // being refused locally: a local refusal is what made "rejected" indistinguishable from "not sent".
        assertEquals(1, http.requests().size(), "the request goes out even without a credential");
        assertEquals(Optional.empty(), http.requests().get(0).headers().firstValue("Authorization"),
                "no credential means no Authorization header, not an empty one");
        assertTrue(sink.only().success(), sink.only()::toString);
    }

    @Test
    void aProviderThatRefusesAnEmptyCredentialIsReportedAsUnauthorized() throws Exception {
        LlmModelCatalogResult result = fetch(new StubHttpClient(401, "{\"error\":\"missing key\"}"), "  ",
                LlmModelCatalogKeySource.NONE);

        assertEquals(LlmModelCatalogState.UNAUTHORIZED, result.state());
        assertEquals("tianshu.gui.llm.catalog.unauthorized", result.messageKey());
        assertEquals("401", result.detail(), "the status the provider stated is the actionable part");
    }

    @Test
    void aCredentialThatCannotBeAHeaderValueNeverSendsTheRequest() {
        StubHttpClient http = new StubHttpClient(200, OPENAI_LIST_BODY);
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(message -> { }, http, Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels(BASE_URL, "sk-injected\r\nX-Evil: 1", LlmModelCatalogKeySource.TYPED, sink);

        assertEquals(LlmModelCatalogState.CREDENTIAL_NOT_SENDABLE, sink.only().state());
        assertEquals("tianshu.gui.llm.catalog.credential_not_sendable", sink.only().messageKey());
        assertTrue(http.requests().isEmpty(), "the transport would reject the value and quote it back");
    }

    @Test
    void aFailureNamesItsOriginEndpointAndStatusButNeverTheCredential() {
        List<String> lines = new ArrayList<>();
        StubHttpClient http = new StubHttpClient(401, "{\"error\":\"invalid key\"}");
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(lines::add, http, Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels(BASE_URL, API_KEY, LlmModelCatalogKeySource.PENDING, sink);

        assertEquals(1, lines.size(), "one failure is one diagnostic line: " + lines);
        String line = lines.get(0);
        assertTrue(line.contains("state=UNAUTHORIZED"), line);
        assertTrue(line.contains("http_status=401"), line);
        assertTrue(line.contains("key_source=pending"), line);
        assertTrue(line.contains("endpoint=" + BASE_URL + "/models"), line);
        assertFalse(line.contains(API_KEY), "the diagnostic must never carry the credential: " + line);
        assertFalse(line.contains("sk-"), "not even a fragment of it: " + line);
    }

    @Test
    void aTransportFailureNamesTheCauseInsteadOfAStatus() {
        List<String> lines = new ArrayList<>();
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(
                lines::add, new FailingHttpClient(), Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels(BASE_URL, API_KEY, LlmModelCatalogKeySource.TYPED, sink);

        assertEquals(1, lines.size(), lines.toString());
        String line = lines.get(0);
        assertTrue(line.contains("state=NETWORK_ERROR"), line);
        assertTrue(line.contains("detail=ExecutionException"), line);
        assertTrue(line.contains("key_source=typed"), line);
        assertFalse(line.contains("http_status="), "there was no answer to quote a status from: " + line);
    }

    @Test
    void aNonHttpEndpointNeverSendsTheRequest() {
        StubHttpClient http = new StubHttpClient(200, OPENAI_LIST_BODY);
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(message -> { }, http, Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels("api.deepseek.com/v1", API_KEY, LlmModelCatalogKeySource.STORED, sink);

        assertEquals(LlmModelCatalogState.MISSING_ENDPOINT, sink.only().state());
        assertEquals("tianshu.gui.llm.catalog.missing_endpoint", sink.only().messageKey());
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void aFailedSendIsANetworkErrorWithoutTheCredentialInIt() throws Exception {
        LlmModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(
                message -> { }, new FailingHttpClient(), Duration.ofSeconds(1));
        RecordingSink sink = new RecordingSink();

        client.listModels(BASE_URL, API_KEY, LlmModelCatalogKeySource.STORED, sink);

        LlmModelCatalogResult result = sink.only();
        assertEquals(LlmModelCatalogState.NETWORK_ERROR, result.state());
        assertEquals("tianshu.gui.llm.catalog.network_error", result.messageKey());
        assertFalse(result.toString().contains(API_KEY), "a result must never echo the credential");
    }

    @Test
    void theUnwiredClientAnswersInsteadOfPretendingTheProviderHasNoModels() {
        RecordingSink sink = new RecordingSink();

        LlmModelCatalogClient.NOT_WIRED.listModels(BASE_URL, API_KEY, LlmModelCatalogKeySource.STORED, sink);

        assertEquals(LlmModelCatalogState.NOT_WIRED, sink.only().state());
        assertEquals("tianshu.gui.llm.catalog.not_wired", sink.only().messageKey());
    }

    @Test
    void theModuleServiceReportsAnUnboundClientWithoutAsking() {
        LlmModuleService service = new LlmModuleService(new StubConfiguration(), new LlmUsageLedger());
        RecordingSink sink = new RecordingSink();

        service.requestModelCatalog(BASE_URL, API_KEY, LlmModelCatalogKeySource.TYPED, sink);

        assertEquals(LlmModelCatalogState.NOT_WIRED, sink.only().state());
    }

    @Test
    void theModuleServiceAnswersExactlyOnceForAMisbehavingClient() {
        LlmModuleService service = new LlmModuleService(new StubConfiguration(), new LlmUsageLedger());
        service.bindModelCatalogClient((baseUrl, apiKey, keySource, sink) -> {
            sink.accept(LlmModelCatalogResult.success(List.of("one")));
            sink.accept(LlmModelCatalogResult.success(List.of("two")));
        });
        RecordingSink sink = new RecordingSink();

        service.requestModelCatalog(BASE_URL, API_KEY, LlmModelCatalogKeySource.TYPED, sink);

        assertEquals(1, sink.count());
        assertEquals(List.of("one"), sink.only().modelIds());
    }

    @Test
    void theModuleServiceReportsOnceWhenTheClientThrows() {
        LlmModuleService service = new LlmModuleService(new StubConfiguration(), new LlmUsageLedger());
        service.bindModelCatalogClient((baseUrl, apiKey, keySource, sink) -> {
            throw new IllegalStateException("broken wiring");
        });
        RecordingSink sink = new RecordingSink();

        service.requestModelCatalog(BASE_URL, API_KEY, LlmModelCatalogKeySource.TYPED, sink);

        assertEquals(1, sink.count());
        assertEquals(LlmModelCatalogState.NETWORK_ERROR, sink.only().state());
        assertEquals("IllegalStateException", sink.only().detail());
    }

    private static LlmModelCatalogResult fetch(HttpClient http) throws Exception {
        return fetch(http, API_KEY, LlmModelCatalogKeySource.STORED);
    }

    private static LlmModelCatalogResult fetch(HttpClient http, String apiKey, LlmModelCatalogKeySource keySource)
            throws Exception {
        OpenAiCompatibleModelCatalogClient client = new OpenAiCompatibleModelCatalogClient(
                message -> { }, http, Duration.ofSeconds(5));
        CompletableFuture<LlmModelCatalogResult> answer = new CompletableFuture<>();
        client.listModels(BASE_URL, apiKey, keySource, answer::complete);
        return answer.get(5, TimeUnit.SECONDS);
    }

    /** Collects the answers a sink received, in order. */
    private static final class RecordingSink implements java.util.function.Consumer<LlmModelCatalogResult> {
        private final List<LlmModelCatalogResult> results = new ArrayList<>();

        @Override
        public synchronized void accept(LlmModelCatalogResult result) {
            results.add(result);
        }

        private synchronized int count() {
            return results.size();
        }

        private synchronized LlmModelCatalogResult only() {
            assertEquals(1, results.size(), "the sink must be invoked exactly once");
            return results.get(0);
        }
    }

    /** Answers with a fixed status and body, and records the requests it saw. */
    private static class StubHttpClient extends HttpClient {
        private final int status;
        private final String body;
        private final List<HttpRequest> requests = new ArrayList<>();

        private StubHttpClient(int status, String body) {
            this.status = status;
            this.body = body;
        }

        private synchronized List<HttpRequest> requests() {
            return List.copyOf(requests);
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
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
            throw new UnsupportedOperationException("the client under test sends asynchronously");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> bodyHandler) {
            synchronized (this) {
                requests.add(request);
            }
            return CompletableFuture.completedFuture((HttpResponse<T>) new StubResponse(status, request, body));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> bodyHandler,
                                                               HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, bodyHandler);
        }
    }

    /** Fails the send, which is what a DNS, proxy or timeout failure looks like from the caller's side. */
    private static final class FailingHttpClient extends StubHttpClient {
        private FailingHttpClient() {
            super(200, "");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> bodyHandler) {
            return CompletableFuture.failedFuture(new ExecutionException(
                    new java.net.ConnectException("connection refused")));
        }
    }

    private static final class StubResponse implements HttpResponse<String> {
        private final int status;
        private final HttpRequest request;
        private final String body;

        private StubResponse(int status, HttpRequest request, String body) {
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
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    /** Minimal configuration; the catalog seam only needs the service, not the settings. */
    private static final class StubConfiguration implements LlmConfiguration {
        @Override
        public boolean isLlmEnabled() {
            return true;
        }

        @Override
        public String getCustomLlmName() {
            return "";
        }

        @Override
        public Path getLlmBasePath() {
            return Path.of(".");
        }
    }

    @Test
    void theServiceKeepsTheBoundClientAcrossCalls() {
        LlmModuleService service = new LlmModuleService(new StubConfiguration(), new LlmUsageLedger());
        AtomicInteger calls = new AtomicInteger();
        service.bindModelCatalogClient((baseUrl, apiKey, keySource, sink) -> {
            calls.incrementAndGet();
            sink.accept(LlmModelCatalogResult.success(List.of("deepseek-flash")));
        });
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();

        service.requestModelCatalog(BASE_URL, API_KEY, LlmModelCatalogKeySource.PENDING, first);
        service.requestModelCatalog(BASE_URL, API_KEY, LlmModelCatalogKeySource.PENDING, second);

        assertEquals(2, calls.get());
        assertEquals(List.of("deepseek-flash"), first.only().modelIds());
        assertEquals(List.of("deepseek-flash"), second.only().modelIds());
        assertNotNull(second.only().state());
    }
}
