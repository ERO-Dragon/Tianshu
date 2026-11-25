package com.rheinmetal.tianshu.dubhe.shared.web;

import com.rheinmetal.tianshu.api.LogSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The one way 天枢 fetches a web resource: bounded, quiet, and never in the caller's way.
 *
 * <p>Every online source in this module goes through here, so the limits are stated once instead of being
 * re-invented per site: an 8 second budget (a player is waiting), half a megabyte of body (a page, not a
 * dataset), at most three redirects that must stay on the same host (a source must not walk us somewhere
 * else), and a fixed user agent so an operator can see who is asking.</p>
 *
 * <p>There is no retry. A failed fetch means the source is unusable right now, and retrying turns one
 * question into three times the delay; the caller degrades to the next source instead.</p>
 *
 * <p>Proxies are explicitly <b>not</b> consulted: a client machine often has a proxy for one purpose and the
 * player did not ask us to route a wiki lookup through it. The JDK client is the only transport.</p>
 */
public final class DubheWeb {

    public static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** A page, not a dataset: anything larger is not an answer we would have used anyway. */
    public static final int MAX_BYTES = 512 * 1024;

    public static final int MAX_REDIRECTS = 3;

    private static final String USER_AGENT = "tianshu-dubhe/1 (+Minecraft mod; guide lookup)";

    private static final CompletableFuture<Optional<String>> EMPTY =
            CompletableFuture.completedFuture(Optional.empty());

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    private DubheWeb() {
    }

    /**
     * Fetches {@code url} as text.
     *
     * <p>Empty for every kind of "no": bad URL, non-HTTP scheme, timeout, non-200, oversized or unreadable
     * body. The caller cannot tell those apart on purpose — from a module's point of view they all mean "this
     * source did not answer", and the next source gets a turn.</p>
     */
    public static CompletableFuture<Optional<String>> get(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException failure) {
            return EMPTY;
        }
        return fetch(uri, 0);
    }

    /** The seam a test replaces; production always uses {@link DubheWeb#get(String)}. */
    @FunctionalInterface
    public interface Fetcher {
        CompletableFuture<Optional<String>> get(String url);
    }

    private static CompletableFuture<Optional<String>> fetch(URI uri, int redirects) {
        if (!isWeb(uri)) {
            return EMPTY;
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
                    .GET()
                    .build();
        } catch (IllegalArgumentException failure) {
            return EMPTY;
        }
        return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(response -> follow(uri, response, redirects))
                .exceptionally(failure -> {
                    LogSink.SYSTEM.warn("web.get.failed url=" + uri, failure);
                    return Optional.empty();
                });
    }

    private static CompletableFuture<Optional<String>> follow(URI uri, HttpResponse<InputStream> response,
                                                              int redirects) {
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                Optional<String> location = response.headers().firstValue("location");
                if (location.isEmpty() || redirects >= MAX_REDIRECTS) {
                    return EMPTY;
                }
                URI next = uri.resolve(location.get());
                if (!sameHost(uri, next)) {
                    return EMPTY;
                }
                return fetch(next, redirects + 1);
            }
            if (status != 200) {
                return EMPTY;
            }
            return CompletableFuture.completedFuture(read(body));
        } catch (IOException failure) {
            return EMPTY;
        }
    }

    /** Reads at most {@link #MAX_BYTES}; the stream is closed by the caller's try-with-resources. */
    private static Optional<String> read(InputStream stream) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            int room = MAX_BYTES - collected.size();
            if (room <= 0) {
                break;
            }
            collected.write(buffer, 0, Math.min(read, room));
        }
        return Optional.of(collected.toString(StandardCharsets.UTF_8));
    }

    private static boolean isWeb(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return ("http".equals(scheme) || "https".equals(scheme)) && uri.getHost() != null;
    }

    private static boolean sameHost(URI from, URI to) {
        return isWeb(to) && from.getHost().equalsIgnoreCase(to.getHost());
    }
}