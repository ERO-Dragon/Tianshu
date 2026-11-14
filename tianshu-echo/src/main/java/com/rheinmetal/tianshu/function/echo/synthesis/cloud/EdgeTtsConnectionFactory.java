package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

/**
 * Opens one websocket attempt.
 *
 * <p>A seam rather than a direct JDK call so the transport's framing, ordering and cancellation can be
 * exercised without a network. The real implementation owns the JDK websocket; this interface owns nothing.
 */
public interface EdgeTtsConnectionFactory {

    /**
     * @param url           the complete connection URL, token and version included
     * @param userAgent     the browser string that matches the credential's version; the endpoint refuses a mismatch
     * @param timeoutMillis budget for the handshake and for each read
     */
    EdgeTtsConnection open(String url, String userAgent, long timeoutMillis);
}