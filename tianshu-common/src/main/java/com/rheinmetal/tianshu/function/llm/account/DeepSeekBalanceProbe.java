package com.rheinmetal.tianshu.function.llm.account;

import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The provider's account balance, read from {@code GET {origin}/user/balance} with the bearer
 * credential.
 *
 * <p>The documented answer is {@code {"is_available": true, "balance_infos": [{"currency": "CNY",
 * "total_balance": "110.00", "granted_balance": "10.00", "topped_up_balance": "100.00"}]}}; see
 * https://api-docs.deepseek.com/api/get-user-balance (read 2026-11). The amounts are decimal strings and
 * one answer may carry several currencies, so {@link LlmAccountBalanceParser} converts them exactly and
 * this class picks the line the UI shows: the provider's CNY balance when it has one, otherwise the first
 * line it listed. The chosen currency travels inside the snapshot, so an amount is never shown as a yuan
 * figure just because the previous provider was Chinese.
 *
 * <p>Every request is sent through {@link HttpClient#sendAsync}: the call returns immediately, the sink
 * runs on a transport thread, and a slow or hanging endpoint can only delay the number. Nothing here
 * invents one — a request that made no usable answer leaves the snapshot failed with
 * {@link LlmAccountSnapshot#UNKNOWN_AMOUNT} amounts, which the page renders as unknown instead of zero.
 *
 * <p>The credential is only ever written into the {@code Authorization} header. It is not logged, not
 * echoed into a snapshot and not part of the diagnostic line, which names the outcome, the HTTP status
 * and the currency and nothing else. Amounts are not logged either: a balance is account data.
 */
public final class DeepSeekBalanceProbe implements LlmAccountProbe {
    /** Path suffix of the documented balance endpoint; it hangs off the API origin, not off the model path. */
    private static final String BALANCE_PATH = "/user/balance";
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
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final String DIAGNOSTIC_PREFIX = "[LLM] account_balance";

    private final LlmConfiguration config;
    private final Consumer<String> diagnostics;
    private final HttpClient httpClient;
    private final Duration timeout;

    public DeepSeekBalanceProbe(LlmConfiguration config, Consumer<String> diagnostics) {
        this(config, diagnostics, null, null);
    }

    /** Transport seam: tests drive the real request/response path with a stub {@link HttpClient}. */
    DeepSeekBalanceProbe(LlmConfiguration config, Consumer<String> diagnostics, HttpClient httpClient, Duration timeout) {
        this.config = config;
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = httpClient != null ? httpClient : newHttpClient();
    }

    @Override
    public void query(LlmAccountKey key, Consumer<LlmAccountSnapshot> sink) {
        Consumer<LlmAccountSnapshot> target = sink == null ? snapshot -> { } : sink;
        LlmAccountKey accountKey = key == null ? LlmAccountKey.unspecified() : key;
        String endpoint = balanceEndpoint(config.getLlmRemoteBaseUrl());
        if (endpoint.isEmpty()) {
            target.accept(failure(accountKey, LlmAccountFailure.MISSING_ENDPOINT, ""));
            return;
        }
        String credential = config.getLlmRemoteApiKey() == null ? "" : config.getLlmRemoteApiKey().trim();
        if (credential.isEmpty()) {
            target.accept(failure(accountKey, LlmAccountFailure.MISSING_CREDENTIAL, ""));
            return;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header(AUTHORIZATION_HEADER, BEARER_PREFIX + credential)
                    .header(ACCEPT_HEADER, ACCEPT_JSON)
                    .GET()
                    .build();
        } catch (IllegalArgumentException unusableCredential) {
            // The JDK quotes the offending header value in its own message, so the key must not get there.
            target.accept(failure(accountKey, LlmAccountFailure.CREDENTIAL_NOT_SENDABLE, ""));
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null
                        ? interpret(accountKey, response)
                        : networkFailure(accountKey))
                .thenAccept(target);
    }

    /** Maps one HTTP answer onto a snapshot; the body is only read on success. */
    private LlmAccountSnapshot interpret(LlmAccountKey key, HttpResponse<String> response) {
        int status = response.statusCode();
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return failure(key, LlmAccountFailure.UNAUTHORIZED, Integer.toString(status));
        }
        if (status != HTTP_OK) {
            return failure(key, LlmAccountFailure.HTTP_ERROR, Integer.toString(status));
        }
        Optional<List<LlmAccountBalanceParser.Balance>> parsed =
                LlmAccountBalanceParser.parseBalances(response.body());
        if (parsed.isEmpty()) {
            return failure(key, LlmAccountFailure.MALFORMED_RESPONSE, "");
        }
        Optional<LlmAccountBalanceParser.Balance> selected = LlmAccountBalanceParser.preferred(parsed.get());
        long nowMillis = System.currentTimeMillis();
        if (selected.isEmpty()) {
            // A valid answer that carried no figure: unknown, not zero.
            return LlmAccountSnapshot.available(key, LlmAccountSnapshot.UNKNOWN_AMOUNT,
                    LlmAccountSnapshot.UNKNOWN_AMOUNT, LlmAccountSnapshot.UNKNOWN_AMOUNT, "", nowMillis);
        }
        LlmAccountBalanceParser.Balance balance = selected.get();
        return LlmAccountSnapshot.available(key, LlmAccountSnapshot.UNKNOWN_AMOUNT,
                LlmAccountSnapshot.UNKNOWN_AMOUNT, balance.totalBalanceMicros(), balance.currencyCode(), nowMillis);
    }

    /** A failed send: DNS, proxy, TLS or timeout. The answer is a failure, never a numeric fallback. */
    private LlmAccountSnapshot networkFailure(LlmAccountKey key) {
        return failure(key, LlmAccountFailure.NETWORK_ERROR, "");
    }

    /**
     * Answers one failed query and names it in the diagnostic line.
     *
     * <p>The line is what tells "no key was configured", "the key was refused" and "the endpoint never
     * answered" apart from a log. It carries the outcome token, the HTTP status when the provider stated
     * one, and the currency when an answer was read far enough to know it — never the credential and never
     * an amount.
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
     * {@code baseUrl}'s origin plus {@link #BALANCE_PATH}.
     *
     * <p>The balance endpoint hangs off the origin the provider documents, not off the versioned model
     * path: {@code https://api.deepseek.com/v1} and {@code https://api.deepseek.com} both answer at
     * {@code https://api.deepseek.com/user/balance}. A trailing API version segment is therefore dropped
     * before the path is appended; any other path the player configured is kept, because a proxy may well
     * serve the balance under its own prefix.
     *
     * @return empty when the base URL is blank or not http(s); the caller reports that as a state
     */
    static String balanceEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (!normalized.startsWith(HTTP_PREFIX) && !normalized.startsWith(HTTPS_PREFIX)) {
            return "";
        }
        while (normalized.endsWith(SEGMENT_SEPARATOR)) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String origin = withoutApiVersion(normalized);
        return origin.endsWith(BALANCE_PATH) ? origin : origin + BALANCE_PATH;
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

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
