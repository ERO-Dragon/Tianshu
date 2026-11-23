package com.rheinmetal.tianshu.weaving.account;

import com.rheinmetal.tianshu.weaving.settings.LlmBalanceSpec;
import com.rheinmetal.tianshu.weaving.settings.WeavingConfiguration;
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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The provider balance seam across every documented answer shape: what each document parses to,
 * which currency is shown, where the request goes, and what every failure reports.
 *
 * <p>One guarantee runs through all of it — a query that made no usable answer leaves the amount
 * unknown, never zero — and one structural rule: the shape is entry data, so the same probe serves
 * DeepSeek, Kimi and an OpenAI-billing relay without a code path per provider.</p>
 */
final class ProviderBalanceProbeTest {
    private static final String DEEPSEEK_BASE = "https://api.deepseek.com/v1";
    private static final String MOONSHOT_BASE = "https://api.moonshot.cn/v1";
    private static final String OPENAI_BASE = "https://api.openai.com/v1";
    private static final String API_KEY = "sk-balance-secret";
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
    private static final String MOONSHOT_BODY = """
            {"code": 0, "data": {"available_balance": 49.58894,
             "voucher_balance": 46.58893, "cash_balance": 3.00001}, "status": true}""";
    private static final String BILLING_SUBSCRIPTION_BODY = """
            {"object": "billing_subscription", "hard_limit_usd": 100.0, "soft_limit_usd": 90.0}""";
    /** {@code total_usage} is cents: 1234 cents is $12.34. */
    private static final String BILLING_USAGE_BODY = """
            {"object": "list", "total_usage": 1234.0}""";

    private static final LlmBalanceSpec DEEPSEEK = new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.DEEPSEEK, "");
    private static final LlmBalanceSpec MOONSHOT = new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.MOONSHOT, "");
    private static final LlmBalanceSpec BILLING = new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.OPENAI_BILLING, "");

    @Test
    void readsTheDeepSeekDocumentAndKeepsItsCurrency() {
        Harness harness = harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, CNY_BODY));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(110_000_000L, snapshot.balanceMicros(), "110.00 in micro units");
        assertEquals("CNY", snapshot.currencyCode());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.todayCostMicros(),
                "the provider publishes no cost endpoint, so this stays unknown");
        HttpRequest request = harness.http().requests().get(0);
        assertEquals("https://api.deepseek.com/user/balance", request.uri().toString(),
                "the balance endpoint hangs off the origin, not off the versioned model path");
        assertEquals("GET", request.method());
        assertEquals(Optional.of("Bearer " + API_KEY), request.headers().firstValue("Authorization"));
    }

    @Test
    void prefersCnyWhenTheAnswerCarriesSeveralCurrencies() {
        LlmAccountSnapshot snapshot = harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, MULTI_CURRENCY_BODY)).query();

        assertEquals("CNY", snapshot.currencyCode());
        assertEquals(110_000_000L, snapshot.balanceMicros());
    }

    @Test
    void fallsBackToTheFirstListedCurrencyWhenThereIsNoCny() {
        LlmAccountSnapshot snapshot = harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, NO_CNY_BODY)).query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state(), "is_available false is not a failure");
        assertEquals("EUR", snapshot.currencyCode());
        assertEquals(7_500_000L, snapshot.balanceMicros());
    }

    @Test
    void anEmptyBalanceListIsAValidAnswerWithoutAFigure() {
        LlmAccountSnapshot snapshot = harness(DEEPSEEK_BASE, DEEPSEEK,
                responds(200, "{\"is_available\":true,\"balance_infos\":[]}")).query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(LlmAccountSnapshot.UNKNOWN_AMOUNT, snapshot.balanceMicros());
        assertEquals("", snapshot.currencyCode());
        assertFalse(LlmAccountSnapshot.amountKnown(snapshot.balanceMicros()));
    }

    @Test
    void readsTheKimiDocumentInUsd() {
        Harness harness = harness(MOONSHOT_BASE, MOONSHOT, responds(200, MOONSHOT_BODY));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(49_588_940L, snapshot.balanceMicros(), "49.58894 in micro units");
        assertEquals("USD", snapshot.currencyCode(), "the Kimi document states its unit in the docs, not the body");
        assertEquals("https://api.moonshot.cn/v1/users/me/balance",
                harness.http().requests().get(0).uri().toString(),
                "Kimi's balance lives under the same versioned prefix the model path uses");
    }

    @Test
    void readsTheOpenAiBillingPairAsCapMinusSpend() {
        Harness harness = harness(OPENAI_BASE, BILLING, byPath(Map.of(
                "/v1/dashboard/billing/subscription", new StubResponse(200, BILLING_SUBSCRIPTION_BODY),
                "/v1/dashboard/billing/usage", new StubResponse(200, BILLING_USAGE_BODY))));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(87_660_000L, snapshot.balanceMicros(), "$100.00 minus 1234 cents");
        assertEquals("USD", snapshot.currencyCode());
        assertEquals(2, harness.http().requests().size(), "the pair needs two requests");
        assertEquals("https://api.openai.com/v1/dashboard/billing/subscription",
                harness.http().requests().get(0).uri().toString());
        assertEquals("https://api.openai.com/v1/dashboard/billing/usage",
                harness.http().requests().get(1).uri().toString());
    }

    @Test
    void aBillingAccountThatSpentPastItsCapShowsNothingLeftRatherThanANegativeAmount() {
        Harness harness = harness(OPENAI_BASE, BILLING, byPath(Map.of(
                "/v1/dashboard/billing/subscription", new StubResponse(200, BILLING_SUBSCRIPTION_BODY),
                "/v1/dashboard/billing/usage", new StubResponse(200, "{\"total_usage\": 20000.0}"))));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.AVAILABLE, snapshot.state());
        assertEquals(0L, snapshot.balanceMicros());
    }

    @Test
    void oneUnreadableHalfOfTheBillingPairMakesTheRemainderUnknown() {
        Harness harness = harness(OPENAI_BASE, BILLING, byPath(Map.of(
                "/v1/dashboard/billing/subscription", new StubResponse(200, BILLING_SUBSCRIPTION_BODY),
                "/v1/dashboard/billing/usage", new StubResponse(200, "<html>gateway</html>"))));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.MALFORMED_RESPONSE.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
    }

    @Test
    void aProviderThatPublishesNoBalanceAsksNothingAndSaysWhy() {
        Harness harness = harness(OPENAI_BASE, LlmBalanceSpec.NONE, responds(200, CNY_BODY));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.UNSUPPORTED.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
        assertTrue(harness.http().requests().isEmpty(), "there is no endpoint to call");
        assertTrue(harness.diagnostics().stream().anyMatch(line -> line.contains("state=unsupported")),
                harness.diagnostics()::toString);
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
    void aRejectedKeyIsReportedAsUnauthorized() {
        Harness harness = harness(DEEPSEEK_BASE, DEEPSEEK, responds(401, "{\"error\":\"invalid key\"}"));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.UNAUTHORIZED.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
        assertTrue(harness.diagnostics().stream().anyMatch(line -> line.contains("state=unauthorized")
                        && line.contains("http_status=401")),
                harness.diagnostics()::toString);
    }

    @Test
    void anyOtherStatusIsAnHttpError() {
        Harness harness = harness(DEEPSEEK_BASE, DEEPSEEK, responds(503, ""));

        LlmAccountSnapshot snapshot = harness.query();

        assertEquals(LlmAccountQueryState.FAILED, snapshot.state());
        assertEquals(LlmAccountFailure.HTTP_ERROR.messageKey(), snapshot.failureKey());
        assertTrue(harness.diagnostics().stream().anyMatch(line -> line.contains("http_status=503")),
                harness.diagnostics()::toString);
    }

    @Test
    void anUnreadableAnswerIsReportedAsMalformed() {
        assertEquals(LlmAccountQueryState.FAILED,
                harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, "<html>gateway</html>")).query().state());
        assertEquals(LlmAccountFailure.MALFORMED_RESPONSE.messageKey(),
                harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, "{\"error\":{\"message\":\"no balance\"}}"))
                        .query().failureKey());
        assertEquals(LlmAccountFailure.MALFORMED_RESPONSE.messageKey(),
                harness(MOONSHOT_BASE, MOONSHOT, responds(200, "{\"data\":{}}")).query().failureKey());
    }

    @Test
    void aMissingCredentialNeverSendsTheRequest() {
        StubHttpClient http = responds(200, CNY_BODY);
        List<String> diagnostics = new ArrayList<>();
        LlmAccountProbe probe = new ProviderBalanceProbe(
                new StubConfig(DEEPSEEK_BASE, "  ", DEEPSEEK), diagnostics::add, http, Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(LlmAccountKey.of(DEEPSEEK_BASE, API_KEY), answer::complete);

        assertEquals(LlmAccountFailure.MISSING_CREDENTIAL.messageKey(), snapshot(answer).failureKey());
        assertTrue(http.requests().isEmpty(), "a request without a credential cannot succeed, so it is not sent");
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("state=missing_credential")),
                diagnostics::toString);
    }

    @Test
    void aNonHttpEndpointNeverSendsTheRequest() {
        StubHttpClient http = responds(200, CNY_BODY);
        LlmAccountProbe probe = new ProviderBalanceProbe(
                new StubConfig("api.deepseek.com/v1", API_KEY, DEEPSEEK), message -> { }, http, Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(LlmAccountKey.of(DEEPSEEK_BASE, API_KEY), answer::complete);

        assertEquals(LlmAccountFailure.MISSING_ENDPOINT.messageKey(), snapshot(answer).failureKey());
        assertTrue(http.requests().isEmpty());
    }

    @Test
    void aFailedSendIsANetworkError() {
        LlmAccountProbe probe = new ProviderBalanceProbe(new StubConfig(DEEPSEEK_BASE, API_KEY, DEEPSEEK),
                message -> { }, failing(), Duration.ofSeconds(1));
        CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();

        probe.query(LlmAccountKey.of(DEEPSEEK_BASE, API_KEY), answer::complete);

        LlmAccountSnapshot snapshot = snapshot(answer);
        assertEquals(LlmAccountFailure.NETWORK_ERROR.messageKey(), snapshot.failureKey());
        assertUnknownAmounts(snapshot);
    }

    @Test
    void theDiagnosticLineNamesTheCauseAndNeverTheCredentialOrAnAmount() {
        Harness unauthorized = harness(DEEPSEEK_BASE, DEEPSEEK, responds(401, "{\"error\":\"invalid key\"}"));
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
    void aSuccessfulAnswerIsNotLogged() {
        Harness harness = harness(DEEPSEEK_BASE, DEEPSEEK, responds(200, CNY_BODY));

        harness.query();

        assertTrue(harness.diagnostics().isEmpty(), "only failures belong in the log: " + harness.diagnostics());
    }

    @Test
    void theEndpointDependsOnTheShapeNotOnTheProviderName() {
        assertEquals("https://api.deepseek.com/user/balance",
                ProviderBalanceProbe.endpointFor(DEEPSEEK, "https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com/user/balance",
                ProviderBalanceProbe.endpointFor(DEEPSEEK, "https://api.deepseek.com/v1/"),
                "trailing slashes collapse");
        assertEquals("https://proxy.example/llm/user/balance",
                ProviderBalanceProbe.endpointFor(new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.DEEPSEEK, "/user/balance"),
                        "https://proxy.example/llm"),
                "a configured path is kept: a proxy may serve the balance under its own prefix");
        assertEquals("https://api.moonshot.cn/v1/users/me/balance",
                ProviderBalanceProbe.endpointFor(MOONSHOT, "https://api.moonshot.cn/v1"));
        assertEquals("https://relay.example/v1/dashboard/billing/subscription",
                ProviderBalanceProbe.endpointFor(BILLING, "https://relay.example/v1"));
        assertEquals("", ProviderBalanceProbe.endpointFor(DEEPSEEK, "api.deepseek.com/v1"));
        assertEquals("", ProviderBalanceProbe.endpointFor(DEEPSEEK, null));
        assertEquals("", ProviderBalanceProbe.endpointFor(LlmBalanceSpec.NONE, "https://api.openai.com/v1"));
    }

    @Test
    void theUsageEndpointSitsBesideTheSubscriptionEndpoint() {
        assertEquals("https://api.openai.com/v1/dashboard/billing/usage",
                ProviderBalanceProbe.usageEndpoint("https://api.openai.com/v1/dashboard/billing/subscription"));
        assertEquals("https://relay.example/billing/usage",
                ProviderBalanceProbe.usageEndpoint("https://relay.example/billing"),
                "an endpoint without the documented last segment still gets one");
        assertEquals("", ProviderBalanceProbe.usageEndpoint(""));
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

    private static Harness harness(String baseUrl, LlmBalanceSpec balance, StubHttpClient http) {
        return new Harness(baseUrl, balance, http);
    }

    /** One probe plus everything a test wants to look at afterwards. */
    private static final class Harness {
        private final String baseUrl;
        private final StubHttpClient http;
        private final List<String> diagnostics = new ArrayList<>();

        private Harness(String baseUrl, LlmBalanceSpec balance, StubHttpClient http) {
            this.baseUrl = baseUrl;
            this.http = http;
            this.probe = new ProviderBalanceProbe(new StubConfig(baseUrl, API_KEY, balance), diagnostics::add,
                    http, Duration.ofSeconds(5));
        }

        private final LlmAccountProbe probe;

        private StubHttpClient http() {
            return http;
        }

        private List<String> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private LlmAccountSnapshot query() {
            CompletableFuture<LlmAccountSnapshot> answer = new CompletableFuture<>();
            probe.query(LlmAccountKey.of(baseUrl, API_KEY), answer::complete);
            return snapshot(answer);
        }
    }

    /** The configuration the probe reads: endpoint, credential and the entry's balance shape. */
    private record StubConfig(String baseUrl, String apiKey, LlmBalanceSpec balance) implements WeavingConfiguration {
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

        @Override
        public LlmBalanceSpec getLlmBalanceSpec() {
            return balance;
        }
    }

    /** Answers with whatever the responder returns for the request, and records what it saw. */
    private static class StubHttpClient extends HttpClient {
        private final Function<HttpRequest, StubResponse> responder;
        private final List<HttpRequest> requests = new ArrayList<>();

        private StubHttpClient(Function<HttpRequest, StubResponse> responder) {
            this.responder = responder;
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
            StubResponse response = responder.apply(request);
            return CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                               HttpResponse.BodyHandler<T> bodyHandler,
                                                               HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, bodyHandler);
        }
    }

    private static StubHttpClient responds(int status, String body) {
        return new StubHttpClient(request -> new StubResponse(status, body));
    }

    /** Routes by the request's path, so the billing pair sees its own answer on each half. */
    private static StubHttpClient byPath(Map<String, StubResponse> responses) {
        return new StubHttpClient(request -> {
            String path = request.uri().getPath();
            StubResponse response = responses.get(path);
            return response == null ? new StubResponse(404, "{\"error\":\"not found: " + path + "\"}") : response;
        });
    }

    /** Fails the send, which is what a DNS, proxy or timeout failure looks like from the caller's side. */
    private static HttpClient failing() {
        return new StubHttpClient(request -> new StubResponse(200, "")) {
            @Override
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                                                                   HttpResponse.BodyHandler<T> bodyHandler) {
                return CompletableFuture.failedFuture(new ExecutionException(
                        new java.net.ConnectException("connection refused")));
            }
        };
    }

    private static final class StubResponse implements HttpResponse<String> {
        private final int status;
        private final String body;
        private HttpRequest request;

        private StubResponse(int status, String body) {
            this.status = status;
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
            return java.net.URI.create("https://stub.invalid/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}