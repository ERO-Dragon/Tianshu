package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

/**
 * Where the platform hands one raw world event to the protocol.
 *
 * <p>Events are produced where the game shows them — the client adapter and the optional server component — and
 * consumed by modules. The publisher owns the topic, so a producer never builds an envelope and a consumer never
 * learns which side the fact was born on. It is also the seam that keeps the host out of a feature: publishing
 * needs no module to exist, and a topic with no subscriber completes silently.</p>
 */
@FunctionalInterface
public interface WorldEventPublisher {

    /** Publishes one occurrence; an aggregation window belongs to whoever consumes the stream. */
    void publish(WorldEventPayload event);
}
