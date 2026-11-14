package com.rheinmetal.tianshu.function.echo.synthesis.cloud;

import java.util.function.Consumer;

/**
 * One websocket attempt, reduced to what the transport needs.
 *
 * <p>An interface rather than a JDK type so the transport's framing, ordering, cancellation and failure
 * mapping can be exercised without a network — which is the part most likely to regress when the provider
 * changes something.</p>
 */
public interface EdgeTtsConnection extends AutoCloseable {

    void send(String frame);

    /**
     * Reads until the provider ends the turn.
     *
     * @param onBinary receives each binary payload verbatim, header block included
     */
    void receive(Consumer<byte[]> onBinary);

    @Override
    void close();
}