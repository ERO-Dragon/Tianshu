package com.rheinmetal.tianshu.function.auxilium.event;

import com.rheinmetal.tianshu.api.LogSink;

/**
 * Runtime entry point for game events: decides, then pushes the entry to the history.
 *
 * <p>This is the only object a producer needs. It owns the dispatcher (tiers, blacklist, speech gate,
 * aggregation windows) so a caller cannot accidentally build a second decision path, and it is closed
 * for the rest of a session once {@link #stop(long)} runs, which is what makes a world logout stop all
 * production even if a platform hook keeps ticking.
 *
 * <p>Speech is not delivered yet: the layer only reports the pending sentence key through the log so
 * the voice wiring can pick it up later without this layer knowing anything about TTS.
 */
public final class AXGameEventService {
    private final AXEventDispatcher dispatcher;
    private final AXEventHistorySink historySink;
    private final LogSink logSink;
    private boolean closed;

    public AXGameEventService(AXEventDispatcher dispatcher, AXEventHistorySink historySink, LogSink logSink) {
        this.dispatcher = dispatcher == null ? new AXEventDispatcher() : dispatcher;
        this.historySink = historySink;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    /**
     * Decides one event and performs its history side effect.
     *
     * @param nowMillis monotonic wall clock of the calling tick
     */
    public void submit(AXEventDescriptor descriptor, long nowMillis) {
        if (closed || descriptor == null) {
            return;
        }
        AXEventDecision decision = dispatcher.decide(descriptor, nowMillis);
        AXEventText speech = decision.speech();
        if (speech != null) {
            // Player facing wording is a language key plus arguments; this layer never renders it.
            logSink.info("ax.event.speech_pending kind=" + descriptor.kind()
                    + " action=" + decision.action().name()
                    + " key=" + speech.key());
        }
        append(decision.appendText() == null
                ? null
                : new AXEventAppend.Single(descriptor.kind(), decision.appendText()));
        for (AXEventAppend due : dispatcher.drainDueAppends(nowMillis)) {
            append(due);
        }
    }

    /** Closes every open aggregation window; call before the turn is assembled or the world unloads. */
    public void flush(long nowMillis) {
        if (closed) {
            return;
        }
        for (AXEventAppend pending : dispatcher.flushAppends()) {
            append(pending);
        }
    }

    /** Flushes and closes the service; later submissions from a stale platform hook are ignored. */
    public void stop(long nowMillis) {
        if (closed) {
            return;
        }
        flush(nowMillis);
        closed = true;
    }

    public boolean closed() {
        return closed;
    }

    private void append(AXEventAppend append) {
        if (append == null || historySink == null) {
            return;
        }
        historySink.append(append);
    }
}
