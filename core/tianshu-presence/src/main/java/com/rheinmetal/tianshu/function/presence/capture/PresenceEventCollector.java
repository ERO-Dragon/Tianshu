package com.rheinmetal.tianshu.function.presence.capture;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import java.util.Objects;

/**
 * Feeds the platform's world-fact stream into the aggregator and publishes the windows that close.
 *
 * <p>Nothing leaves here per occurrence: {@link #recordWorldEvent} only adds to the current second, and
 * {@link #flushWorldEvents} — called on the client tick — publishes one payload per kind whose second has elapsed.
 * Draining after the world session ended is suppressed, and pending windows are dropped rather than published into
 * the next world.</p>
 *
 * <p>The platform hands over raw single occurrences ({@link WorldEventPayload}); the window, the grouping and the
 * re-publication are this module's, so the host never sees an aggregated payload.</p>
 */
public final class PresenceEventCollector {
    private final PresenceWorldEventAggregator aggregator;
    private final LogSink logSink;
    private PresenceWorldEventSink worldEventSink = PresenceWorldEventSink.NOOP;
    private volatile boolean worldSessionActive;

    public PresenceEventCollector(LogSink logSink) {
        this(logSink, new PresenceWorldEventAggregator());
    }

    /** Test seam: a fake clock lives in the aggregator, so the collector stays a thin adapter. */
    public PresenceEventCollector(LogSink logSink, PresenceWorldEventAggregator aggregator) {
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
    }

    public void setWorldEventSink(PresenceWorldEventSink worldEventSink) {
        this.worldEventSink = worldEventSink == null ? PresenceWorldEventSink.NOOP : worldEventSink;
    }

    /**
     * One occurrence, grouped with the other occurrences of the same kind in the same second.
     *
     * <p>The raw event is the platform's shape; this is where it becomes a presence window. A chat line, an
     * advancement and a block broken are the same thing here — a kind with values — so nothing downstream switches on
     * where the fact was born.</p>
     */
    public void recordWorldEvent(WorldEventPayload event) {
        if (!worldSessionActive || event == null) {
            return;
        }
        aggregator.accept(new PresenceWorldEventPayload(
                null,
                event.eventType(),
                event.playerId(),
                event.dimensionId(),
                event.occurredAtMillis(),
                event.values()
        ));
    }

    /** Publishes every closed window; the only path from this collector to the protocol sink. */
    public void flushWorldEvents() {
        if (!worldSessionActive) {
            aggregator.reset();
            return;
        }
        PresenceWorldEventAggregator.Drain drain = aggregator.drain();
        if (drain.isEmpty()) {
            return;
        }
        for (PresenceWorldEventPayload payload : drain.payloads()) {
            worldEventSink.publish(payload);
        }
        drain.capped().forEach((kind, dropped) ->
                // Overflowing the group cap is a producer bug, not player data: the kind and the count are all a
                // diagnostic needs, and no value ever reaches the log.
                logSink.warn("presence.world_event_capped kind=" + kind + " dropped=" + dropped));
    }

    public void startWorldSession() {
        worldSessionActive = true;
        aggregator.reset();
    }

    public void stopWorldSession() {
        worldSessionActive = false;
        aggregator.reset();
    }
}
