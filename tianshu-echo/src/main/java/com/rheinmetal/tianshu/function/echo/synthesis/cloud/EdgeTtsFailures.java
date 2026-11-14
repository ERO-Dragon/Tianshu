package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Turns what a socket can throw into the module's failure vocabulary.
 *
 * <p>Kept as pure functions so the mapping is testable without a server: the difference between "the network
 * is down", "the credential was refused" and "we are being rate limited" is exactly what the preview text
 * has to tell the player apart.</p>
 */
public final class EdgeTtsFailures {

    private EdgeTtsFailures() {
    }

    /** A websocket handshake that came back with an HTTP status. */
    public static TtsFailureException fromStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return new TtsFailureException(TtsFailureCode.CLOUD_AUTH_FAILED,
                    "Edge TTS refused the credential (HTTP " + statusCode + ")");
        }
        if (statusCode == 429) {
            return new TtsFailureException(TtsFailureCode.CLOUD_RATE_LIMITED,
                    "Edge TTS rate limited the request (HTTP 429)");
        }
        return new TtsFailureException(TtsFailureCode.CLOUD_UNAVAILABLE,
                "Edge TTS handshake failed (HTTP " + statusCode + ")");
    }

    /** Anything thrown while opening or reading the connection. */
    public static TtsFailureException fromThrowable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        if (cause instanceof TtsFailureException structured) {
            return structured;
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) {
            return new TtsFailureException(TtsFailureCode.CLOUD_TIMEOUT,
                    "Edge TTS timed out: " + message(cause));
        }
        if (cause instanceof IOException) {
            return new TtsFailureException(TtsFailureCode.CLOUD_UNAVAILABLE,
                    "Edge TTS connection failed: " + message(cause));
        }
        return new TtsFailureException(TtsFailureCode.CLOUD_UNAVAILABLE,
                "Edge TTS failed: " + message(cause));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && !(current instanceof TtsFailureException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private static String message(Throwable throwable) {
        String message = throwable == null ? "" : throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }
}