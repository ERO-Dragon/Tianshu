package com.rheinmetal.tianshu.function.auxilium.event;

import com.rheinmetal.tianshu.api.LogSink;

/**
 * Public event entry point: decides one submitted event, then pushes the entry to the history.
 *
 * <p>This is the only object a producer needs, and it is module agnostic on purpose: any module may
 * submit any namespaced kind. The AX module owns one instance per session, so the service is the
 * single decision path - tiers, blacklist, speech gate and aggregation windows - and a caller cannot
 * accidentally build a second one.
 *
 * <p>Discovery uses the existing service registry in both directions:
 *
 * <ul>
 *   <li>a managed module reads it from its own runtime context,
 *       {@code context.services().find(AXGameEventService.class)};</li>
 *   <li>a platform layer reads it from the core manager,
 *       {@code coreManager.findService(AXGameEventService.class)}.</li>
 * </ul>
 *
 * <p>No caller is privileged and no source is special cased: {@link #submit(AXEventDescriptor)} takes
 * the same descriptor from a third-party module and from the built-in game event producer, and
 * {@link AXEventDescriptor#sourceId()} is recorded, never branched on.
 *
 * <p>The service is closed for the rest of a session once {@link #stop(long)} runs, which is what makes
 * a world logout stop all production even if a platform hook keeps ticking.
 *
 * <p>Speech leaves through {@link AXEventSpeechSink}: the pending sentence is the producer's own text,
 * reported in the log for diagnostics and handed to the sink for the voice channel. The layer itself
 * still knows nothing about TTS, wording or playback order.
 */
public final class AXGameEventService {
    private final AXEventDispatcher dispatcher;
    private final AXEventHistorySink historySink;
    private final AXEventSpeechSink speechSink;
    private final LogSink logSink;
    private boolean closed;

    public AXGameEventService(AXEventDispatcher dispatcher, AXEventHistorySink historySink, LogSink logSink) {
        this(dispatcher, historySink, AXEventSpeechSink.NOOP, logSink);
    }

    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            LogSink logSink) {
        this.dispatcher = dispatcher == null ? new AXEventDispatcher() : dispatcher;
        this.historySink = historySink;
        this.speechSink = speechSink == null ? AXEventSpeechSink.NOOP : speechSink;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    /** Submits one event stamped with the wall clock; the call any module makes. */
    public void submit(AXEventDescriptor descriptor) {
        submit(descriptor, System.currentTimeMillis());
    }

    /**
     * Submits one event decided at an explicit clock reading.
     *
     * @param nowMillis monotonic wall clock of the calling tick
     */
    public void submit(AXEventDescriptor descriptor, long nowMillis) {
        if (closed || descriptor == null) {
            return;
        }
        AXEventDecision decision = dispatcher.decide(descriptor, nowMillis);
        if (decision.action().speaks()) {
            // The pending utterance is the producer's sentence, passed through unchanged: this layer
            // never renders wording of its own. The log line is the diagnostic; the sink is the voice
            // channel (arrival order is FIFO, queuing is the TTS module's business).
            logSink.info("ax.event.speech_pending kind=" + descriptor.kind()
                    + " source=" + descriptor.sourceId()
                    + " action=" + decision.action().name()
                    + " text=" + descriptor.text());
            speechSink.speak(descriptor.kind(), descriptor.text());
        }
        if (decision.appends() && !decision.folded()) {
            append(new AXEventAppend.Single(descriptor.kind(), descriptor.text()));
        }
        for (AXEventAppend due : dispatcher.drainDueAppends(nowMillis)) {
            append(due);
        }
    }

    /** Closes every open aggregation window as of now; used outside a tick that carries a clock. */
    public void flush() {
        flush(System.currentTimeMillis());
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
