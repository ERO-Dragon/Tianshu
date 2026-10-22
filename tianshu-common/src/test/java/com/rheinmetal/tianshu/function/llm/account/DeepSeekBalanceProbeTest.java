package com.rheinmetal.tianshu.function.llm.account;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider balance seam: what the documented answer parses to, which currency is shown, and what
 * every failure reports. One guarantee runs through all of it — a query that made no usable answer
 * leaves the amount unknown, never zero.
 */
final class DeepSeekBalanceProbeTest {
    private static final String BASE_URL = "https://api.deepseek.com/v1";
    private static final String API_KEY = "sk-balance-secret";
    private static final LlmAccountKey KEY = LlmAccountKey.of(BASE_URL, API_KEY);
    private static final String CNY_BODY = """
            {"is_available": true, "balance_infos": [
              {"currency": "CNY", "total_balance": "110.00",
               "granted_balance": "10.00", "topped_up_balance": "100.00"}
            ]}""";
    private static final String MULTI_CURRENCY_BODY = """
            {"is_available": true, "balance_infos": [
              {"currency": "USD", "total_balance": "20.00", "granted_balance": "0.00", "topped_up_balance": "20.00"},
              {"currency": "CNY", "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}
            ]}""";
    private static final String NO_CNY_BODY = """
            {"is_available": false, "balance_infos": [
              {"currency": "EUR", "total_balance": "7.50", "granted_balance": "0.00", "topped_up_balance": "7.50"}
            ]}""";

    @Test
    void readsTheDocumentedBalanceAndKeepsItsCurrency() throws Exception {
        RecordingProbe probe = probe(200, CNY_BODY);

        LlmAccountSnapshot snapshot = probe.query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(110_000_000L, snapshot.balanceMicros(), "110.00 in micro units");
        assertEquals("CNY", snapshot.currencyCode());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.todayCostMicros(),
                "the provider publishes no cost endpoint, so this stays unknown");
        HttpRequest request = probe.http().requests().get(0);
        assertEquals("https://api.deepseek.com/user/balance", request.uri().toString(),
                "the balance endpoint hangs off the origin, not off the versioned model path");
        assertEquals("GET", request.method());
        assertEquals(Optional.of("Bearer " + API_KEY), request.headers().firstValue("Authorization"));
    }

    @Test
    void prefersCnyWhenTheAnswerCarriesSeveralCurrencies() throws Exception {
        LlmAccountSnapshot snapshot = probe(200, MULTI_CURRENCY_BODY).query();

        assertEquals("CNY", snapshot.currencyCode());
        assertEquals(110_000_000L, snapshot.balanceMicros());
    }

    @Test
    void fallsBackToTheFirstListedCurrencyWhenThereIsNoCny() throws Exception {
        LlmAccountSnapshot snapshot = probe(200, NO_CNY_BODY).query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state(), "is_available false is not a failure");
        assertEquals("EUR", snapshot.currencyCode());
        assertEquals(7_500_000L, snapshot.balanceMicros());
    }

    @Test
    void anEmptyBalanceListIsAValidAnswerWithoutAFigure() throws Exception {
        LlmAccountSnapshot snapshot = probe(200, "{\"is_available\":true,\"balance_infos\":[]}").query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.balanceMicros());
        assertEquals("", snapshot.currencyCode());
        assertFalse(LlmAccountSnapshot.amountKnown(snapshot.balanceMicros()));
    }

    @Test
    void amountsAreConvertedExactlyWithoutFloatingPoint() {
        assertEquals(110_000_000L, LlmAccountBalanceParser.microsOf("110.00"));
        assertEquals(15_000L, LlmAccountBalanceParser.microsOf("0.015"));
        assertEquals(0L, LlmAccountBalanceParser.microsOf("0"));
        assertEquals(1_234_567_890_000L, LlmAccountBalanceParser.microsOf("1234567.89"));
        assertEquals(1_000_001L, LlmAccountBalanceParser.microsOf("1.0000019"),
                "digits beyond the sixth decimal are dropped, never rounded up");
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, LlmAccountBalanceParser.microsOf(""));
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, LlmAccountBalanceParser.microsOf("n/a"));
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, LlmAccountBalanceParser.microsOf("-1.00"));
    }

    @Test
    void aRejectedKeyIsReportedAsUnauthorized() throws Exception {
        RecordingProbe probe = probe(401, "{\"error\":\"invalid key\"}");

        LlmAccountSnapshot snapshot = probe.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.UNAUTHORIZED.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
        assertTrue(probe.diagnostics().stream().anyMatch(line -> line.contains("state=unauthorized")
                        && line.contains("http_status=401")),
                probe.diagnostics()::toString);
    }

    @Test
    void anyOtherStatusIsAnHttpError() throws Exception {
        RecordingProbe probe = probe(503, "");

        LlmAccountSnapshot snapshot = probe.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.HTTP_ERROR.messageKey(), snapshot.failureKey());
        assertTrue(probe.diagnostics().stream().anyMatch(line -> line.contains("http_status=503")),
                probe.diagnostics()::toString);
    }

    @Test
    void anUnreadableAnswerIsReportedAsMalformed() throws Exception {
        assertEquals(LlmAccountQueryState.FAILED, probe(200, "<html>gateway</html>").query().state());
        assertEquals(LlmAccountFailure.MALFORMED_RESPONSE.messageKey(),
                probe(200, "{\"error\":{\"message\":\"no balance\"}}").query().failureKey());
    }

    @Test
    void aMissingCredentialNeverSendsTheRequest() {
        StubHttpClient http = new StubHttpClient(200, CNY_BODY);
        List<String> diagnostics = new ArrayList<>();
        LlmAccountProbe probe = new DeepSeekBalanceProbe(new StubConfig(BASE_URL, "  "), diagnostics::add,
                http, Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(KEY, answer::complete);

        assertEquals(LlmAccountFailure.MISSING_CREDENTIAL.messageKey(), snapshot(answer).failureKey());
        assertTrue(http.requests().isEmpty(), "a request without a credential cannot succeed, so it is not sent");
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("state=missing_credential")),
                diagnostics::toString);
    }

    @Test
    void aNonHttpEndpointNeverSendsTheRequest() {
        StubHttpClient http = new StubHttpClient(200, CNY_BODY);
        LlmAccountProbe probe = new DeepSeekBalanceProbe(new StubConfig("api.deepseek.com/v1", API_KEY),
                message -> { }, http, Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(KEY, answer::complete);

        assertEquals(LlmAccountFailure.MISSING_ENDPOINT.messageKey(), snapshot(answer).failureKey());
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void aFailedSendIsANetworkError() {
        LlmAccountProbe probe = new DeepSeekBalanceProbe(new StubConfig(BASE_URL, API_KEY), message -> { },
                new FailingHttpClient(), Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(KEY, answer::complete);

        LlmAccountSnapshot snapshot = snapshot(answer);
        assertEquals(LlmAccountFailure.NETWORK_ERROR.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
    }

    @Test
    void theDiagnosticLineNamesTheCauseAndNeverTheCredentialOrAnAmount() throws Exception {
        RecordingProbe unauthorized = probe(401, "{\"error\":\"invalid key\"}");
        unauthorized.query();

        String line = unauthorized.diagnostics().get(0);
        assertTrue(line.startsWith("[LLM] account_balance state=unauthorized"), line);
        assertTrue(line.contains("http_status=401"), line);
        assertTrue(line.contains("currency="), line);
        assertFalse(line.contains(API_KEY), "the diagnostic must never carry the credential: " + line);
        assertFalse(line.contains("sk-"), "not even a fragment of it: " + line);
        assertFalse(line.contains("110.00"), "and never an amount: " + line);
    }

    @Test
    void aSuccessfulAnswerIsNotLogged() throws Exception {
        RecordingProbe probe = probe(200, CNY_BODY);

        probe.query();

        assertTrue(probe.diagnostics().isEmpty(), "only failures belong in the log: " + probe.diagnostics());
    }

    @Test
    void theBalanceEndpointHangsOffTheOrigin() {
        assertEquals("https://api.deepseek.com/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint("https://api.deepseek.com/v1/"));
        assertEquals("https://api.deepseek.com/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint(" https://api.deepseek.com "));
        assertEquals("https://api.deepseek.com/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint("https://api.deepseek.com/v1beta"));
        assertEquals("https://proxy.example/llm/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint("https://proxy.example/llm"),
                "a configured path is kept: a proxy may serve the balance under its own prefix");
        assertEquals("https://api.deepseek.com/user/balance",
                DeepSeekBalanceProbe.balanceEndpoint("https://api.deepseek.com/user/balance"));
        assertEquals("", DeepSeekBalanceProbe.balanceEndpoint("api.deepseek.com/v1"));
        assertEquals("", DeepSeekBalanceProbe.balanceEndpoint(null));
    }

    private static void assertUnknownAmounts(LlmAccountSnapshot snapshot) {
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.balanceMicros(),
                "a failed query must not report a zero balance");
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.todayCostMicros());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.totalCostMicros());
        assertTrue(snapshot.fetchedAtMillis() > 0L);
    }

    private static LlmAccountSnapshot snapshot(CompletableFuture<LlmAccountSnapshot> answer) {
        try {
            return answer.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        } catch (ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw new AssertionError(failure);
        }
    }

    private static RecordingProbe probe(int status, String body) {
        return new RecordingProbe(new StubHttpClient(status, body));
    }

    /** One probe plus everything a test wants to look at afterwards. */
    private static final class RecordingProbe {
        private final StubHttpClient http;
        private final List<String> diagnostics = new ArrayList<>();
        private final LlmAccountProbe probe;

        private RecordingProbe(StubHttpClient http) {
            this.http = http;
            this.probe = new DeepSeekBalanceProbe(new StubConfig(BASE_URL, API_KEY), diagnostics::add,
                    http, Duration.ofSeconds(5));
        }

        private StubHttpClient http() {
            return http;
        }

        private List<String> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private LlmAccountSnapshot query() {
            CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();
            probe.query(KEY, answer::complete);
            return snapshot(answer);
        }
    }

    /** The configuration the probe reads: endpoint and credential, nothing else. */
    private record StubConfig(String baseUrl, String apiKey) implements LlmConfiguration {
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

        @Override
        public String getLlmRemoteBaseUrl() {
            return baseUrl;
        }

        @Override
        public String getLlmRemoteApiKey() {
            return apiKey;
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
            throw new UnsupportedOperationException("the probe under test sends asynchronously");
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
}
