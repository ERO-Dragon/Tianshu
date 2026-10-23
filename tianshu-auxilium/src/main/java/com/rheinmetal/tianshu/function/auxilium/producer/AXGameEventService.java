package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventClass;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventClassBuffer;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDecision;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDispatcher;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventHistorySink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTierTable;
import com.rheinmetal.tianshu.api.LogSink;

import java.util.List;

/**
 * Public event entry point: decides one submitted event, then performs what its class says.
 *
 * <p>This is the only object a producer needs, and it is module agnostic on purpose: any module may submit any
 * namespaced kind. The AX module owns one instance per session, so the service is the single decision path — tiers,
 * the attention list, the frequency step, the two holding areas and the fold windows — and a caller cannot
 * accidentally build a second one.
 *
 * <p>Discovery uses the existing service registry in both directions: a managed module reads it from its own runtime
 * context ({@code context.services().find(AXGameEventService.class)}), a platform layer from the core manager
 * ({@code coreManager.findService(AXGameEventService.class)}).
 *
 * <p>What each class does here:
 *
 * <ul>
 *   <li>{@link AXEventClass#IMMEDIATE} — spoken on arrival, or held in the immediate queue when the assistant is
 *       busy and the player does not allow interruptions;</li>
 *   <li>{@link AXEventClass#DEFERRED} — held in the deferred buffer until the cooldown window closes, then spoken as
 *       one voluntary turn;</li>
 *   <li>{@link AXEventClass#SILENT} — folded into the history with a count, never spoken;</li>
 *   <li>{@link AXEventClass#DROP} — nothing at all.</li>
 * </ul>
 *
 * <p>Both holding areas are emptied when the user speaks first ({@link #beforeUserTurn(long)}): their lines become
 * history entries in arrival order, before the user's own turn, which is what keeps the history in the order the
 * session happened and lets the compaction chain fold them into memory later.
 *
 * <p>This is where an event becomes a sentence. The producer states a template and its arguments, the service renders
 * it through the injected {@link AXGameEventTextProvider}, and that one rendered line is what the decision stores,
 * the history receives and the voice channel speaks. Rendering happens once per submission, before the decision.
 *
 * <p>The service is closed for the rest of a session once {@link #stop(long)} runs, which is what makes a world
 * logout stop all production even if a platform hook keeps ticking.
 */
public final class AXGameEventService {
    /** Diagnostic emitted when a template cannot be worded and the entry falls back to the kind code. */
    private static final String UNRESOLVED = "ax.event.text.unresolved";

    /** Separator between the sentences of one voluntary turn; the layer holds no wording of its own. */
    private static final String VOLUNTARY_TURN_SEPARATOR = "\n";

    private final AXEventDispatcher dispatcher;
    private final AXEventHistorySink historySink;
    private final AXEventSpeechSink speechSink;
    private final AXGameEventTextProvider texts;
    private final AXEventSettings settings;
    private final LogSink logSink;
    private final AXEventClassBuffer buffer;
    private boolean closed;

    public AXGameEventService(AXEventDispatcher dispatcher, AXEventHistorySink historySink, LogSink logSink) {
        this(dispatcher, historySink, AXEventSpeechSink.NOOP, AXGameEventTextProvider.NONE, AXEventSettings.DEFAULT, logSink);
    }

    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, AXGameEventTextProvider.NONE, AXEventSettings.DEFAULT, logSink);
    }

    /**
     * @param texts the language table this session renders through; it is handed to the fold windows as well, so a
     *              folded line is worded by the same table and never by the producer
     */
    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, texts, AXEventSettings.DEFAULT, logSink);
    }

    public AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            AXEventSettings settings,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, texts, settings, null, logSink);
    }

    /**
     * Builds the decision path itself, with the renderer wired to the same language table.
     *
     * <p>Preferred over passing a dispatcher in: the fold templates must be rendered through this provider, and
     * constructing both here makes it impossible to wire one without the other.
     */
    public AXGameEventService(
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            LogSink logSink) {
        this(historySink, speechSink, texts, AXEventSettings.DEFAULT, logSink);
    }

    public AXGameEventService(
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            AXEventSettings settings,
            LogSink logSink) {
        this(new AXEventDispatcher(AXEventTierTable.builtin(), settings, texts::render),
                historySink, speechSink, texts, settings, logSink);
    }

    /**
     * Same wiring, with the holding areas supplied by the caller.
     *
     * <p>Used by tests that need a pinned cooldown window: the jitter is the one part of the deferred buffer that is
     * random by design, so a test that asserts "not yet, then spoken" has to inject its own.
     */
    public AXGameEventService(
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            AXEventSettings settings,
            AXEventClassBuffer buffer,
            LogSink logSink) {
        this(new AXEventDispatcher(AXEventTierTable.builtin(), settings, texts::render),
                historySink, speechSink, texts, settings, buffer, logSink);
    }

    private AXGameEventService(
            AXEventDispatcher dispatcher,
            AXEventHistorySink historySink,
            AXEventSpeechSink speechSink,
            AXGameEventTextProvider texts,
            AXEventSettings settings,
            AXEventClassBuffer buffer,
            LogSink logSink) {
        this.dispatcher = dispatcher == null ? new AXEventDispatcher() : dispatcher;
        this.historySink = historySink;
        this.speechSink = speechSink == null ? AXEventSpeechSink.NOOP : speechSink;
        this.texts = texts == null ? AXGameEventTextProvider.NONE : texts;
        this.settings = settings == null ? AXEventSettings.DEFAULT : settings;
        this.buffer = buffer == null ? new AXEventClassBuffer(this.settings) : buffer;
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
        String sentence = sentenceOf(descriptor);
        AXEventDecision decision = dispatcher.decide(descriptor, sentence, nowMillis);
        switch (decision.eventClass()) {
            case DROP -> {
                // Nothing at all: no line, no utterance. The decision is still returned to the caller as a whole,
                // so a producer that logs its own submissions can see the drop.
            }
            case SILENT -> {
                if (!decision.folded()) {
                    append(new AXEventAppend.Single(descriptor.kind(), sentence));
                }
            }
            case IMMEDIATE -> submitImmediate(descriptor, sentence, nowMillis);
            case DEFERRED -> {
                buffer.defer(descriptor.kind(), sentence, nowMillis);
                logSink.info("ax.event.deferred kind=" + descriptor.kind()
                        + " source=" + descriptor.sourceId()
                        + " buffered=" + buffer.deferredSize());
            }
        }
        drainFolds(nowMillis);
    }

    /**
     * Advances the layer's clocks: closes due fold windows and speaks the deferred batch when its window is over.
     *
     * <p>Call it from the session tick. Without a tick the deferred buffer only ever leaves through a user turn,
     * which would make it a queue instead of a voluntary turn.
     */
    public void tick(long nowMillis) {
        if (closed) {
            return;
        }
        drainFolds(nowMillis);
        speakVoluntaryTurn(buffer.dueDeferred(nowMillis));
    }

    /**
     * The user spoke first: everything waiting becomes history, in arrival order, before the user's own line.
     *
     * <p>This is the rule that keeps the history in the order the session happened, and it is also what turns a
     * queued or deferred event into something the compaction chain will fold into memory instead of something that
     * never happened.
     */
    public void beforeUserTurn(long nowMillis) {
        if (closed) {
            return;
        }
        // Fold windows that are already due belong to the past, so they are appended first.
        drainFolds(nowMillis);
        for (AXEventClassBuffer.Entry entry : buffer.takeForUserTurn()) {
            append(new AXEventAppend.Single(entry.kind(), entry.text()));
        }
    }

    /** Closes every open fold window as of now; used outside a tick that carries a clock. */
    public void flush() {
        flush(System.currentTimeMillis());
    }

    /** Closes every open fold window; call before a turn is assembled or the world unloads. */
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
        // A session that ends with waiting entries still owes them to the history: dropping them here would make the
        // last few events of a world silently disappear.
        beforeUserTurn(nowMillis);
        closed = true;
    }

    public boolean closed() {
        return closed;
    }

    /** Entries waiting in the immediate queue; diagnostics and tests. */
    public int queuedImmediateCount() {
        return buffer.immediateSize();
    }

    /** Entries waiting in the deferred buffer; diagnostics and tests. */
    public int bufferedDeferredCount() {
        return buffer.deferredSize();
    }

    private void submitImmediate(AXEventDescriptor descriptor, String sentence, long nowMillis) {
        if (!settings.busy() || settings.allowInterruption()) {
            speak(descriptor.kind(), sentence, "immediate");
            // The event happened and was said: the history has to carry the same line, or memory would only know about
            // the events that were too quiet to speak.
            append(new AXEventAppend.Single(descriptor.kind(), sentence));
            return;
        }
        buffer.holdImmediate(descriptor.kind(), sentence, nowMillis);
        logSink.info("ax.event.queued kind=" + descriptor.kind()
                + " source=" + descriptor.sourceId()
                + " queued=" + buffer.immediateSize());
    }

    /** One voluntary turn: the deferred batch, spoken once, and the window re-drawn by the buffer. */
    private void speakVoluntaryTurn(List<AXEventClassBuffer.Entry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (AXEventClassBuffer.Entry entry : batch) {
            if (entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(VOLUNTARY_TURN_SEPARATOR);
            }
            joined.append(entry.text().trim());
        }
        if (joined.length() == 0) {
            return;
        }
        String kind = batch.get(0).kind() == null ? "" : batch.get(0).kind();
        logSink.info("ax.event.voluntary_turn kind=" + kind + " merged=" + batch.size());
        speechSink.speak(kind, joined.toString());
        // Each occurrence was its own event even though it was said in one breath, so the history keeps them apart.
        for (AXEventClassBuffer.Entry entry : batch) {
            if (entry.text() != null && !entry.text().isBlank()) {
                append(new AXEventAppend.Single(entry.kind(), entry.text()));
            }
        }
    }

    private void speak(String kind, String sentence, String reason) {
        // The utterance is the sentence this service rendered, passed through unchanged: the event layer holds no
        // wording of its own. The log line is the diagnostic; the sink is the voice channel.
        logSink.info("ax.event.speech_pending kind=" + kind
                + " reason=" + reason
                + " text=" + sentence);
        speechSink.speak(kind, sentence);
    }

    private void drainFolds(long nowMillis) {
        for (AXEventAppend due : dispatcher.drainDueAppends(nowMillis)) {
            append(due);
        }
    }

    /**
     * Renders one descriptor, falling back to the kind code when the template cannot be worded.
     *
     * <p>An empty line would take a history slot without carrying anything, which reads to the model as an event that
     * happened and said nothing — worse than a bare kind code, which at least names what happened.
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
                // A resolvable root whose result is still blank means the arguments failed: a missing nested key, or
                // nesting past AXEventText.MAX_REF_DEPTH. Both report themselves the same way.
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
