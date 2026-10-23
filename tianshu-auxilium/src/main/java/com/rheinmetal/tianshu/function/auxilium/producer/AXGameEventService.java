package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventHistorySink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDispatcher;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDecision;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTierTable;
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
 * <p>This is where an event becomes a sentence. The producer states a template and its arguments, the
 * service renders it through the injected {@link AXGameEventTextProvider}, and that one rendered line is
 * what the decision stores, the history receives and the voice channel speaks. Rendering happens once per
 * submission, before the decision, so the aggregator never has to re-word an occurrence.
 *
 * <p>The service is closed for the rest of a session once {@link #stop(long)} runs, which is what makes
 * a world logout stop all production even if a platform hook keeps ticking.
 */
public final class AXGameEventService {
    /** Diagnostic emitted when a template cannot be worded and the entry falls back to the kind code. */
    private static final String UNRESOLVED = "ax.event.text.unresolved";

    private final AXEventDispatcher dispatcher;
    private final AXEventHistorySink historySink;
    private final AXEventSpeechSink speechSink;
    private final AXGameEventTextProvider texts;
    private final LogSink logSink;
    private boolean closed;

    public AXGameEventService(AXEventDispatcher dispatcher, AXEventHistorySink historySink, LogSink logSink) {
        this(dispatcher, historySink, AXEventSpeechSink.NOOP, AXGameEventTextProvider.NONE, logSink);
    }

    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, AXGameEventTextProvider.NONE, logSink);
    }

    /**
     * @param texts the language table this session renders through; it is handed to the aggregation windows
     *              as well, so a folded line is worded by the same table and never by the producer
     */
    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            LogSink logSink) {
        this.dispatcher = dispatcher == null ? new AXEventDispatcher() : dispatcher;
        this.historySink = historySink;
        this.speechSink = speechSink == null ? AXEventSpeechSink.NOOP : speechSink;
        this.texts = texts == null ? AXGameEventTextProvider.NONE : texts;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    /**
     * Builds the decision path itself, with the renderer wired to the same language table.
     *
     * <p>Preferred over passing a dispatcher in: the aggregate fold templates must be rendered through this
     * provider, and constructing both here makes it impossible to wire one without the other.
     */
    public AXGameEventService(
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            LogSink logSink) {
        this(new AXEventDispatcher(AXEventTierTable.builtin(), AXEventSettings.DEFAULT, texts::render),
                historySink, speechSink, texts, logSink);
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
        String sentence = sentenceOf(descriptor);
        AXEventDecision decision = dispatcher.decide(descriptor, sentence, nowMillis);
        if (decision.action().speaks()) {
            // The pending utterance is the sentence this service rendered, passed through unchanged: the
            // event layer holds no wording of its own. The log line is the diagnostic; the sink is the
            // voice channel (arrival order is FIFO, queuing is the TTS module's business).
            logSink.info("ax.event.speech_pending kind=" + descriptor.kind()
                    + " source=" + descriptor.sourceId()
                    + " action=" + decision.action().name()
                    + " text=" + sentence);
            speechSink.speak(descriptor.kind(), sentence);
        }
        if (decision.appends() && !decision.folded()) {
            append(new AXEventAppend.Single(descriptor.kind(), sentence));
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

    /**
     * Renders one descriptor, falling back to the kind code when the template cannot be worded.
     *
     * <p>An empty line would take a history slot without carrying anything, which reads to the model as an
     * event that happened and said nothing — worse than a bare kind code, which at least names what
     * happened. The kind fallback is the same shape the delegate failure path already uses.
     */
    private String sentenceOf(AXEventDescriptor descriptor) {
        String rendered = AXEventTexts.sanitize(texts.render(descriptor.text()));
        if (!rendered.isBlank()) {
            return rendered;
        }
        String key = descriptor.text().key();
        logSink.info(UNRESOLVED
                + " kind=" + descriptor.kind()
                + " source=" + descriptor.sourceId()
                + " key=" + key
                // A resolvable root whose result is still blank means the arguments failed: a missing nested
                // key, or nesting past AXEventText.MAX_REF_DEPTH. Both report themselves the same way.
                + " reason=" + (texts.exists(key) ? "arguments" : "missing_key"));
        return descriptor.kind();
    }

    private void append(AXEventAppend append) {
        if (append == null || historySink == null) {
            return;
        }
        historySink.append(append);
    }
}
