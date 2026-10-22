package com.rheinmetal.tianshu.function.weaving.catalog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Model list of an OpenAI-compatible endpoint: {@code GET {baseUrl}/models} with a bearer credential.
 *
 * <p>The documented answer is {@code {"object":"list","data":[{"id":...}]}}; see
 * https://platform.openai.com/docs/api-reference/models/list and the mirror DeepSeek publishes at
 * https://api-docs.deepseek.com/api/list-models (both read 2026-11). Only the ids are read, see
 * {@link LlmModelCatalogParser}.
 *
 * <p>Every request is sent through {@link HttpClient#sendAsync}, so the call returns immediately and
 * the sink runs on a transport thread. Nothing here decides what the configuration becomes: a
 * successful answer is a list of ids, and writing it is the caller's decision.
 *
 * <p>The request is sent whatever credential the caller passes, blank included: the page fetches before
 * anything is saved, so only the provider can say whether a key is required or accepted. The one
 * exception is a value that cannot be a header at all, which is reported as
 * {@link LlmModelCatalogState#CREDENTIAL_NOT_SENDABLE}.
 *
 * <p>The credential is only ever written into the {@code Authorization} header. It is not logged, not
 * echoed into a result and not part of the diagnostic line: a failure line names the origin of the
 * credential, the endpoint and the HTTP status, and nothing more.
 */
public final class OpenAiCompatibleModelCatalogClient implements LlmModelCatalogClient {
    /** The provider-path suffix the OpenAI-compatible model list lives at. */
    private static final String MODELS_PATH = "/models";
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_OK = 200;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final Consumer<String> diagnostics;
    private final HttpClient httpClient;
    private final Duration timeout;

    public OpenAiCompatibleModelCatalogClient(Consumer<String> diagnostics) {
        this(diagnostics, null, DEFAULT_TIMEOUT);
    }

    /** Transport seam: tests drive the real request/response path with a stub {@link HttpClient}. */
    OpenAiCompatibleModelCatalogClient(Consumer<String> diagnostics, HttpClient httpClient, Duration timeout) {
        this.diagnostics = diagnostics == null ? message -> { } : diagnostics;
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? DEFAULT_TIMEOUT : timeout;
        this.httpClient = httpClient != null ? httpClient : newHttpClient();
    }

    @Override
    public void listModels(String baseUrl, String apiKey, LlmModelCatalogKeySource keySource,
                           Consumer<LlmModelCatalogResult> sink) {
        Consumer<LlmModelCatalogResult> target = sink == null ? result -> { } : sink;
        LlmModelCatalogKeySource source = keySource == null ? LlmModelCatalogKeySource.NONE : keySource;
        String endpoint = modelsEndpoint(baseUrl);
        if (endpoint.isEmpty()) {
            reportFailure(target, LlmModelCatalogState.MISSING_ENDPOINT, "", source, baseUrl);
            return;
        }
        String credential = apiKey == null ? "" : apiKey.trim();
        if (!credential.isEmpty() && !isHeaderSafe(credential)) {
            // The JDK rejects such a value with an exception that quotes it; keep the key out of that.
            reportFailure(target, LlmModelCatalogState.CREDENTIAL_NOT_SENDABLE, "", source, endpoint);
            return;
        }
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .GET();
            if (!credential.isEmpty()) {
                builder.header(AUTHORIZATION_HEADER, BEARER_PREFIX + credential);
            }
            request = builder.build();
        } catch (IllegalArgumentException unreadableEndpoint) {
            reportFailure(target, LlmModelCatalogState.MISSING_ENDPOINT, "", source, endpoint);
            return;
        }
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> error == null
                        ? interpret(response, source, endpoint)
                        : networkFailure(error, source, endpoint))
                .thenAccept(target::accept);
    }

    /** Maps one HTTP answer onto a catalog result; the body is only read on success. */
    private LlmModelCatalogResult interpret(HttpResponse<String> response, LlmModelCatalogKeySource source,
                                            String endpoint) {
        int status = response.statusCode();
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return reportFailure(LlmModelCatalogState.UNAUTHORIZED, Integer.toString(status), source, endpoint);
        }
        if (status != HTTP_OK) {
            return reportFailure(LlmModelCatalogState.HTTP_ERROR, Integer.toString(status), source, endpoint);
        }
        Optional<List<String>> modelIds = LlmModelCatalogParser.parseModelIds(response.body());
        if (modelIds.isEmpty()) {
            return reportFailure(LlmModelCatalogState.MALFORMED_RESPONSE, "", source, endpoint);
        }
        return LlmModelCatalogResult.success(modelIds.get());
    }

    /**
     * A failed send. The completion exception wraps the transport cause, and its type name is the only
     * diagnostic available; the credential is not part of it because it never left a header.
     */
    private LlmModelCatalogResult networkFailure(Throwable error, LlmModelCatalogKeySource source, String endpoint) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        return reportFailure(LlmModelCatalogState.NETWORK_ERROR, cause.getClass().getSimpleName(), source, endpoint);
    }

    /**
     * Emits the diagnostic line of one failure and answers the caller.
     *
     * <p>The line names what the page chose and what the provider said — the origin of the credential,
     * the endpoint and either the HTTP status or the transport note — which is what tells "no key was
     * sent", "the key was refused" and "the endpoint never answered" apart. It never contains the
     * credential or a fragment of it: {@code key_source} is a fixed token from
     * {@link LlmModelCatalogKeySource}.
     */
    private LlmModelCatalogResult reportFailure(LlmModelCatalogState state, String detail,
                                                LlmModelCatalogKeySource source, String endpoint) {
        LlmModelCatalogResult result = LlmModelCatalogResult.failure(state, detail);
        StringBuilder line = new StringBuilder("[LLM] model_catalog state=").append(state.name());
        if (state.quotesHttpStatus()) {
            line.append(" http_status=").append(detail);
        } else if (!detail.isEmpty()) {
            line.append(" detail=").append(detail);
        }
        line.append(" key_source=").append(source.label())
                .append(" endpoint=").append(endpoint == null ? "" : endpoint);
        diagnostics.accept(line.toString());
        return result;
    }

    private LlmModelCatalogResult reportFailure(Consumer<LlmModelCatalogResult> target, LlmModelCatalogState state,
                                                String detail, LlmModelCatalogKeySource source, String endpoint) {
        LlmModelCatalogResult result = reportFailure(state, detail, source, endpoint);
        target.accept(result);
        return result;
    }

    /**
     * {@code baseUrl} plus {@link #MODELS_PATH}.
     *
     * @return empty when the base URL is blank or not http(s); the caller reports that as a state
     *         rather than sending a request that cannot succeed
     */
    static String modelsEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (!normalized.startsWith(HTTP_PREFIX) && !normalized.startsWith(HTTPS_PREFIX)) {
            return "";
        }
        return normalized.endsWith(MODELS_PATH) ? normalized : normalized + MODELS_PATH;
    }

    private static boolean isHeaderSafe(String apiKey) {
        for (int index = 0; index < apiKey.length(); index++) {
            char character = apiKey.charAt(index);
            if (character < 0x20 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
