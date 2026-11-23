package com.rheinmetal.tianshu.weaving.account;

import com.rheinmetal.tianshu.weaving.runtime.LlmHttpTransport;
import com.rheinmetal.tianshu.weaving.settings.LlmBalanceSpec;
import com.rheinmetal.tianshu.weaving.settings.WeavingConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The provider's account balance, read from whichever endpoint that provider documents.
 *
 * <p>Providers disagree here the same way they disagree on request fields, so the shape and the path
 * come from the entry ({@link LlmBalanceSpec}), which falls back to the shipped provider profile. This
 * class owns only the transport half of that: which endpoint to call, in what order, and what a status
 * code means. How a body is spelled belongs to
 * {@link LlmAccountBalanceParser#readDocument} / {@link LlmAccountBalanceParser#readBilling}, so a new
 * provider is a data entry, not a second transport.</p>
 *
 * <p>Documented endpoints (read 2026-11):</p>
 * <ul>
 *   <li>{@code DEEPSEEK} — {@code GET {origin}/user/balance}, answered with
 *       {@code balance_infos[].total_balance} in the currency the provider states;</li>
 *   <li>{@code MOONSHOT} — {@code GET {baseUrl}/users/me/balance}, answered with
 *       {@code data.available_balance} in USD (platform.kimi.ai/docs/api/balance);</li>
 *   <li>{@code OPENAI_BILLING} — the OpenAI dashboard pair
 *       {@code /dashboard/billing/subscription} + {@code /dashboard/billing/usage}, which is also what
 *       a relay station that speaks the OpenAI billing shape answers.</li>
 * </ul>
 *
 * <p>Nothing here invents a number. A provider that publishes no balance endpoint is reported as
 * {@link LlmAccountFailure#UNSUPPORTED}, a failed request leaves the amount unknown, and a provider
 * that states no currency keeps an empty one rather than borrowing a symbol. The credential only ever
 * travels in the {@code Authorization} header: it is not logged, not echoed into a snapshot and not part
 * of the diagnostic line, which names the outcome, the HTTP status and the currency and nothing else.
 * Amounts are not logged either — a balance is account data.</p>
 */
public final class ProviderBalanceProbe implements LlmAccountProbe {
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private static final String SEGMENT_SEPARATOR = "/";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String ACCEPT_JSON = "application/json";
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final String DIAGNOSTIC_PREFIX = "[LLM] account_balance";
    /** The billing pair's usage endpoint sits beside the subscription endpoint, on the same prefix. */
    private static final String BILLING_SUBSCRIPTION_SUFFIX = "/subscription";
    private static final String BILLING_USAGE_SUFFIX = "/usage";

    private final WeavingConfiguration config;
    private final Consumer<String> diagnostics;
    private final HttpClient httpClient;
    private final Duration timeout;

    public ProviderBalanceProbe(WeavingConfiguration config, Consumer<String> diagnostics) {
        this(config, diagnostics, null, null);
    }

    /** Transport seam: tests drive the real request/response path with a stub {@link HttpClient}. */
    ProviderBalanceProbe(WeavingConfiguration config, Consumer<String> diagnostics, HttpClient httpClient, Duration timeout) {
        this.config = config;
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = httpClient != null ? httpClient : LlmHttpTransport.client();
    }

    @Override
    public void query(LlmAccountKey key, Consumer<LlmAccountSnapshot> sink) {
        Consumer<LlmAccountSnapshot> target = sink == null ? snapshot -> { } : sink;
        LlmAccountKey accountKey = key == null ? LlmAccountKey.unspecified() : key;
        LlmBalanceSpec spec = config.getLlmBalanceSpec();
        if (spec == null || !spec.supportsQuery()) {
            target.accept(failure(accountKey, LlmAccountFailure.UNSUPPORTED, ""));
            return;
        }
        String endpoint = endpointFor(spec, config.getLlmRemoteBaseUrl());
        if (endpoint.isEmpty()) {
            target.accept(failure(accountKey, LlmAccountFailure.MISSING_ENDPOINT, ""));
            return;
        }
        String credential = config.getLlmRemoteApiKey() == null ? "" : config.getLlmRemoteApiKey().trim();
        if (credential.isEmpty()) {
            target.accept(failure(accountKey, LlmAccountFailure.MISSING_CREDENTIAL, ""));
            return;
        }
        switch (spec.wire()) {
            case DEEPSEEK, MOONSHOT -> queryDocument(endpoint, credential, accountKey, spec.wire(), target);
            case OPENAI_BILLING -> queryBilling(endpoint, credential, accountKey, target);
            case NONE -> target.accept(failure(accountKey, LlmAccountFailure.UNSUPPORTED, ""));
        }
    }

    // --- one document -----------------------------------------------------------------------------

    /** One GET whose answer is the whole balance document. */
    private void queryDocument(
            String endpoint,
            String credential,
            LlmAccountKey key,
            LlmBalanceSpec.BalanceWire wire,
            Consumer<LlmAccountSnapshot> target
    ) {
        HttpRequest request;
        try {
            request = get(endpoint, credential);
        } catch (IllegalArgumentException unusableCredential) {
            // The JDK quotes the offending header value in its own message, so the key must not get there.
            target.accept(failure(key, LlmAccountFailure.CREDENTIAL_NOT_SENDABLE, ""));
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null
                        ? interpretDocument(key, wire, response)
                        : failure(key, LlmAccountFailure.NETWORK_ERROR, ""))
                .thenAccept(target);
    }

    private LlmAccountSnapshot interpretDocument(LlmAccountKey key, LlmBalanceSpec.BalanceWire wire, HttpResponse<String> response) {
        LlmAccountSnapshot statusFailure = statusFailure(key, response);
        if (statusFailure != null) {
            return statusFailure;
        }
        Optional<LlmAccountBalanceParser.DocumentBalance> reading =
                LlmAccountBalanceParser.readDocument(wire, response.body());
        if (reading.isEmpty()) {
            return failure(key, LlmAccountFailure.MALFORMED_RESPONSE, "");
        }
        return availableSnapshot(key, reading.get());
    }

    /** A document that was read successfully, whether or not it carried a figure. */
    private static LlmAccountSnapshot availableSnapshot(LlmAccountKey key, LlmAccountBalanceParser.DocumentBalance balance) {
        return LlmAccountSnapshot.available(key, LlmAccountSnapshot.UNKNOWN_AMOUNT,
                LlmAccountSnapshot.UNKNOWN_AMOUNT, balance.amountMicros(), balance.currencyCode(),
                System.currentTimeMillis());
    }

    // --- the billing pair -------------------------------------------------------------------------

    /**
     * The OpenAI billing pair: the subscription states the cap, the usage states what was spent.
     *
     * <p>Two requests because the shape needs two. Neither answer alone is a balance, and a pair that
     * arrives one successful body and one unreadable body is reported as unreadable rather than as a
     * guessed remainder.</p>
     */
    private void queryBilling(String subscriptionEndpoint, String credential, LlmAccountKey key, Consumer<LlmAccountSnapshot> target) {
        HttpRequest subscriptionRequest;
        try {
            subscriptionRequest = get(subscriptionEndpoint, credential);
        } catch (IllegalArgumentException unusableCredential) {
            target.accept(failure(key, LlmAccountFailure.CREDENTIAL_NOT_SENDABLE, ""));
            return;
        }
        String usageEndpoint = usageEndpoint(subscriptionEndpoint);
        httpClient.sendAsync(subscriptionRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenCompose(response -> {
                    LlmAccountSnapshot statusFailure = statusFailure(key, response);
                    if (statusFailure != null) {
                        return CompletableFuture.completedFuture(statusFailure);
                    }
                    String subscriptionBody = response.body();
                    HttpRequest usageRequest;
                    try {
                        usageRequest = get(usageEndpoint, credential);
                    } catch (IllegalArgumentException unusableCredential) {
                        return CompletableFuture.completedFuture(
                                failure(key, LlmAccountFailure.CREDENTIAL_NOT_SENDABLE, ""));
                    }
                    return httpClient.sendAsync(usageRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                            .handle((usageResponse, usageError) -> usageError == null
                                    ? interpretBilling(key, subscriptionBody, usageResponse)
                                    : failure(key, LlmAccountFailure.NETWORK_ERROR, ""));
                })
                .handle((snapshot, error) -> error == null ? snapshot : failure(key, LlmAccountFailure.NETWORK_ERROR, ""))
                .thenAccept(target);
    }

    private LlmAccountSnapshot interpretBilling(LlmAccountKey key, String subscriptionBody, HttpResponse<String> usageResponse) {
        LlmAccountSnapshot statusFailure = statusFailure(key, usageResponse);
        if (statusFailure != null) {
            return statusFailure;
        }
        Optional<LlmAccountBalanceParser.DocumentBalance> reading =
                LlmAccountBalanceParser.readBilling(subscriptionBody, usageResponse.body());
        if (reading.isEmpty()) {
            return failure(key, LlmAccountFailure.MALFORMED_RESPONSE, "");
        }
        return availableSnapshot(key, reading.get());
    }

    // --- shared -----------------------------------------------------------------------------------

    /** The HTTP status as a failure snapshot, or {@code null} when the answer is a body to read. */
    private LlmAccountSnapshot statusFailure(LlmAccountKey key, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return failure(key, LlmAccountFailure.UNAUTHORIZED, Integer.toString(status));
        }
        if (status != HTTP_OK) {
            return failure(key, LlmAccountFailure.HTTP_ERROR, Integer.toString(status));
        }
        return null;
    }

    private HttpRequest get(String endpoint, String credential) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(timeout)
                .header(AUTHORIZATION_HEADER, BEARER_PREFIX + credential)
                .header(ACCEPT_HEADER, ACCEPT_JSON)
                .GET()
                .build();
    }

    /**
     * Answers one failed query and names it in the diagnostic line.
     *
     * <p>The line is what tells "no key was configured", "the key was refused", "this provider
     * publishes no balance" and "the endpoint never answered" apart from a log. It carries the outcome
     * token, the HTTP status when the provider stated one, and the currency when an answer was read far
     * enough to know it — never the credential and never an amount.
     */
    private LlmAccountSnapshot failure(LlmAccountKey key, LlmAccountFailure failure, String httpStatus) {
        StringBuilder line = new StringBuilder(DIAGNOSTIC_PREFIX)
                .append(" state=").append(failure.token());
        if (failure.quotesHttpStatus() && !httpStatus.isEmpty()) {
            line.append(" http_status=").append(httpStatus);
        }
        line.append(" currency=");
        diagnostics.accept(line.toString());
        return LlmAccountSnapshot.failed(key, failure.messageKey(), System.currentTimeMillis());
    }

    /**
     * The endpoint of one spec, resolved against the entry's base URL.
     *
     * <p>Base resolution follows the wire, because the documented endpoints hang off different roots:
     * DeepSeek's balance is served from the origin (its {@code /v1} is a model-path prefix, not part of
     * the account path), while Moonshot and the OpenAI billing pair are served under the same prefix the
     * model path uses. A configured path the player added is always kept — a proxy may serve the balance
     * under its own prefix.</p>
     *
     * @return empty when the base URL is blank or not http(s); the caller reports that as a state
     */
    static String endpointFor(LlmBalanceSpec spec, String baseUrl) {
        if (spec == null || !spec.supportsQuery()) {
            return "";
        }
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (!normalized.startsWith(HTTP_PREFIX) && !normalized.startsWith(HTTPS_PREFIX)) {
            return "";
        }
        while (normalized.endsWith(SEGMENT_SEPARATOR)) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String root = spec.wire() == LlmBalanceSpec.BalanceWire.DEEPSEEK ? withoutApiVersion(normalized) : normalized;
        String path = spec.effectivePath();
        if (path.isEmpty()) {
            return "";
        }
        return root.endsWith(path) ? root : root + path;
    }

    /** The usage endpoint that belongs to a subscription endpoint: same prefix, last segment swapped. */
    static String usageEndpoint(String subscriptionEndpoint) {
        if (subscriptionEndpoint == null || subscriptionEndpoint.isEmpty()) {
            return "";
        }
        if (subscriptionEndpoint.endsWith(BILLING_SUBSCRIPTION_SUFFIX)) {
            return subscriptionEndpoint.substring(0, subscriptionEndpoint.length() - BILLING_SUBSCRIPTION_SUFFIX.length())
                    + BILLING_USAGE_SUFFIX;
        }
        return subscriptionEndpoint + BILLING_USAGE_SUFFIX;
    }

    /** {@code .../v1} and {@code .../v1beta} are version prefixes of the model API, not of the origin. */
    private static String withoutApiVersion(String url) {
        int separator = url.lastIndexOf(SEGMENT_SEPARATOR);
        if (separator <= 0) {
            return url;
        }
        String lastSegment = url.substring(separator + 1);
        boolean versionSegment = lastSegment.length() >= 2
                && (lastSegment.charAt(0) == 'v' || lastSegment.charAt(0) == 'V')
                && Character.isDigit(lastSegment.charAt(1))
                && lastSegment.chars().allMatch(character -> Character.isLetterOrDigit(character));
        return versionSegment ? url.substring(0, separator) : url;
    }
}