package com.rheinmetal.tianshu.function.weaving.runtime;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * The module's one HTTP transport.
 *
 * <p>Every weaving client — inference, account balance, model catalog — talks through this
 * {@link HttpClient}. One per process rather than one per client is what keeps a session from leaving a
 * selector thread and a connection pool behind every time the lane is rebuilt: the lane is rebuilt on
 * each world entry and on each start retry, and the neutral modules still compile against Java 17, where
 * {@code HttpClient} has no {@code close()} to release it with.</p>
 *
 * <p>Timeouts stay per request: every caller sets its own {@code HttpRequest.timeout}, so sharing the
 * client changes only who owns the sockets, not how long a call may take.</p>
 */
public final class LlmHttpTransport {
    /** Long enough for a slow proxy, short enough that an unreachable host does not hold a worker. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private LlmHttpTransport() {
    }

    public static HttpClient client() {
        return Holder.CLIENT;
    }

    /** Lazy so a build that never sends a request never creates the client or its thread. */
    private static final class Holder {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}