package com.rheinmetal.tianshu.client.presence.capture;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.List;
import java.util.Objects;

/**
 * Feeds the world-fact stream into the aggregator and publishes the windows that close.
 *
 * <p>Nothing leaves here per occurrence: {@link #recordPlayerChatMessage} and {@link #recordWorldEvents} only add to
 * the current second, and {@link #flushWorldEvents} — called from the client tick — publishes one payload per kind
 * whose second has elapsed. Draining after the world session ended is suppressed, and pending windows are dropped
 * rather than published into the next world.</p>
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
     * One chat line, grouped with the other lines of the same second.
     *
     * <p>Chat used to have its own payload and its own topic; it is an event kind now, so it is aggregated like
     * every other fact and a chatty second leaves as one payload carrying each speaker's line.</p>
     */
    public void recordPlayerChatMessage(String messageText, String senderId, String senderName) {
        if (!worldSessionActive) {
            return;
        }
        if (messageText == null || messageText.isBlank() || senderId == null || senderId.isBlank()) {
            return;
        }
        aggregator.accept(PresenceWorldEventPayload.chatMessage(
                senderId,
                cleanSenderName(senderName),
                messageText
        ));
    }

    public void recordWorldEvents(List<PresenceWorldEventPayload> events) {
        if (!worldSessionActive || events == null || events.isEmpty()) {
            return;
        }
        for (PresenceWorldEventPayload payload : events) {
            aggregator.accept(payload);
        }
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

    private String cleanSenderName(String senderName) {
        return senderName == null ? "" : senderName.trim();
    }
}
