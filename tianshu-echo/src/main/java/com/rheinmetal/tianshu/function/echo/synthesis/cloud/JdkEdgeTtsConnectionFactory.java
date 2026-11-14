package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The JDK websocket implementation of one Edge attempt.
 *
 * <p>The JDK delivers messages on its own thread, so the listener only queues them; the transport's thread
 * consumes the queue. That keeps the socket thread free and gives every blocking wait the profile's timeout
 * instead of an unbounded hang.</p>
 */
public final class JdkEdgeTtsConnectionFactory implements EdgeTtsConnectionFactory {

    private static final String ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold";

    private static String newMuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Override
    public EdgeTtsConnection open(String url, String userAgent, long timeoutMillis) {
        long budget = timeoutMillis <= 0 ? 15_000L : timeoutMillis;
        QueueingListener listener = new QueueingListener();
        try {
            WebSocket socket = client.newWebSocketBuilder()
                    .header("Origin", ORIGIN)
                    // The endpoint rejects a handshake without the MUID cookie the browser sends; it is a
                    // per-connection random id, not a credential.
                    .header("Cookie", "muid=" + newMuid() + ";")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Pragma", "no-cache")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", userAgent)
                    .connectTimeout(Duration.ofMillis(budget))
                    .buildAsync(URI.create(url), listener)
                    .get(budget, TimeUnit.MILLISECONDS);
            return new JdkConnection(socket, listener, budget);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof WebSocketHandshakeException handshake) {
                throw EdgeTtsFailures.fromStatus(handshake.getResponse().statusCode());
            }
            throw EdgeTtsFailures.fromThrowable(cause == null ? failure : cause);
        } catch (TimeoutException failure) {
            throw EdgeTtsFailures.fromThrowable(failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw EdgeTtsFailures.fromThrowable(failure);
        } catch (RuntimeException failure) {
            throw EdgeTtsFailures.fromThrowable(failure);
        }
    }

    private static final class JdkConnection implements EdgeTtsConnection {
        private final WebSocket socket;
        private final QueueingListener listener;
        private final long timeoutMillis;

        private JdkConnection(WebSocket socket, QueueingListener listener, long timeoutMillis) {
            this.socket = socket;
            this.listener = listener;
            this.timeoutMillis = timeoutMillis;
        }

        @Override
        public void send(String frame) {
            try {
                socket.sendText(frame, true).get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw EdgeTtsFailures.fromThrowable(interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                throw EdgeTtsFailures.fromThrowable(failure.getCause() == null ? failure : failure.getCause());
            }
        }

        @Override
        public void receive(Consumer<byte[]> onBinary) {
            while (true) {
                Object event;
                try {
                    event = listener.events.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw EdgeTtsFailures.fromThrowable(interrupted);
                }
                if (event == null) {
                    throw new TtsFailureException(TtsFailureCode.CLOUD_TIMEOUT,
                            "Edge TTS stopped sending audio before the turn ended");
                }
                if (event instanceof byte[] audio) {
                    onBinary.accept(audio);
                    continue;
                }
                if (event instanceof Throwable failure) {
                    throw EdgeTtsFailures.fromThrowable(failure);
                }
                if (!(event instanceof String text)) {
                    // Closed before turn.end: the provider ended the utterance without saying so. Treating
                    // that as success would hand the caller a truncated clip with no way to notice.
                    throw new TtsFailureException(TtsFailureCode.CLOUD_INVALID_RESPONSE,
                            "Edge TTS connection closed before the turn ended");
                }
                Optional<EdgeTtsProtocol.Frame> frame = EdgeTtsProtocol.parseTextFrame(text);
                if (frame.isPresent() && "turn.end".equals(frame.get().path())) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            try {
                socket.abort();
            } catch (RuntimeException ignored) {
                // Aborting a socket that is already gone is not a failure the caller can act on.
            }
        }
    }

    /** Receives JDK callbacks and hands them to the consuming thread. */
    private static final class QueueingListener implements WebSocket.Listener {
        private static final Object CLOSED = new Object();

        private final BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
        private final StringBuilder text = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            text.append(data);
            if (last) {
                events.add(text.toString());
                text.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binary.writeBytes(chunk);
            if (last) {
                events.add(binary.toByteArray());
                binary.reset();
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            events.add(CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(error);
        }
    }
}