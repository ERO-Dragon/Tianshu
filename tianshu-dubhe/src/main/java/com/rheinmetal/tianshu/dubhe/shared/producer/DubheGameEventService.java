package com.rheinmetal.tianshu.dubhe.shared.producer;

import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventAppend;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventClass;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventClassBuffer;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventDecision;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventDescriptor;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventDispatcher;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventHistorySink;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSettings;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSpeechSink;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventPayload;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventPolicy;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventState;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventText;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventTexts;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventTierTable;
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
 * context ({@code context.services().find(DubheGameEventService.class)}), a platform layer from the core manager
 * ({@code coreManager.findService(DubheGameEventService.class)}).
 *
 * <p>What each class does here:
 *
 * <ul>
 *   <li>{@link DubheEventClass#IMMEDIATE} — spoken on arrival, or held in the immediate queue when the assistant is
 *       busy and the player does not allow interruptions;</li>
 *   <li>{@link DubheEventClass#DEFERRED} — held in the deferred buffer until the cooldown window closes, then spoken as
 *       one voluntary turn;</li>
 *   <li>{@link DubheEventClass#SILENT} — folded into the history with a count, never spoken;</li>
 *   <li>{@link DubheEventClass#DROP} — nothing at all.</li>
 * </ul>
 *
 * <p>Both holding areas are emptied when the user speaks first ({@link #beforeUserTurn(long)}): their lines become
 * history entries in arrival order, before the user's own turn, which is what keeps the history in the order the
 * session happened and lets the compaction chain fold them into memory later.
 *
 * <p>This is where an event becomes a sentence. The producer states a template and its arguments, the service renders
 * it through the injected {@link DubheGameEventTextProvider}, and that one rendered line is what the decision stores,
 * the history receives and the voice channel speaks. Rendering happens once per submission, before the decision.
 *
 * <p>The service is closed for the rest of a session once {@link #stop(long)} runs, which is what makes a world
 * logout stop all production even if a platform hook keeps ticking.
 */
public final class DubheGameEventService {
    /** Diagnostic emitted when a template cannot be worded and the entry falls back to the kind code. */
    private static final String UNRESOLVED = "dubhe.shared.event.text.unresolved";

    /** Separator between the sentences of one voluntary turn; the layer holds no wording of its own. */
    private static final String VOLUNTARY_TURN_SEPARATOR = "\n";

    /** Language key of the separator between two occurrence groups inside one batch line. */
    private static final String BATCH_JOIN_KEY = "tianshu.dubhe.shared.event.batch.join";

    /** How many characters one batch line may spend before it is shortened with a "more" suffix. */
    private static final int MAX_BATCH_CHARS = 50;

    private final DubheEventDispatcher dispatcher;
    private final DubheEventHistorySink historySink;
    private final DubheEventSpeechSink speechSink;
    private final DubheGameEventTextProvider texts;
    private final DubheEventSettings settings;
    private final LogSink logSink;
    private final DubheEventClassBuffer buffer;
    private boolean closed;

    public DubheGameEventService(DubheEventDispatcher dispatcher, DubheEventHistorySink historySink, LogSink logSink) {
        this(dispatcher, historySink, DubheEventSpeechSink.NOOP, DubheGameEventTextProvider.NONE, DubheEventSettings.DEFAULT, logSink);
    }

    public DubheGameEventService(
            DubheEventDispatcher dispatcher,
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, DubheGameEventTextProvider.NONE, DubheEventSettings.DEFAULT, logSink);
    }

    /**
     * @param texts the language table this session renders through; it is handed to the fold windows as well, so a
     *              folded line is worded by the same table and never by the producer
     */
    public DubheGameEventService(
            DubheEventDispatcher dispatcher,
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, texts, DubheEventSettings.DEFAULT, logSink);
    }

    public DubheGameEventService(
            DubheEventDispatcher dispatcher,
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            DubheEventSettings settings,
            LogSink logSink) {
        this(dispatcher, historySink, speechSink, texts, settings, null, logSink);
    }

    /**
     * Builds the decision path itself, with the renderer wired to the same language table.
     *
     * <p>Preferred over passing a dispatcher in: the fold templates must be rendered through this provider, and
     * constructing both here makes it impossible to wire one without the other.
     */
    public DubheGameEventService(
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            LogSink logSink) {
        this(historySink, speechSink, texts, DubheEventSettings.DEFAULT, logSink);
    }

    public DubheGameEventService(
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            DubheEventSettings settings,
            LogSink logSink) {
        this(new DubheEventDispatcher(DubheEventTierTable.builtin(), settings, texts::render),
                historySink, speechSink, texts, settings, logSink);
    }

    /**
     * Same wiring, with the holding areas supplied by the caller.
     *
     * <p>Used by tests that need a pinned cooldown window: the jitter is the one part of the deferred buffer that is
     * random by design, so a test that asserts "not yet, then spoken" has to inject its own.
     */
    public DubheGameEventService(
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            DubheEventSettings settings,
            DubheEventClassBuffer buffer,
            LogSink logSink) {
        this(new DubheEventDispatcher(DubheEventTierTable.builtin(), settings, texts::render),
                historySink, speechSink, texts, settings, buffer, logSink);
    }

    private DubheGameEventService(
            DubheEventDispatcher dispatcher,
            DubheEventHistorySink historySink,
            DubheEventSpeechSink speechSink,
            DubheGameEventTextProvider texts,
            DubheEventSettings settings,
            DubheEventClassBuffer buffer,
            LogSink logSink) {
        this.dispatcher = dispatcher == null ? new DubheEventDispatcher() : dispatcher;
        this.historySink = historySink;
        // The platform advances this layer through the clock; without it the deferred window and the immediate
        // queue would only drain when the player speaks again.
        DubheEventClock.install(this);
        this.speechSink = speechSink == null ? DubheEventSpeechSink.NOOP : speechSink;
        this.texts = texts == null ? DubheGameEventTextProvider.NONE : texts;
        this.settings = settings == null ? DubheEventSettings.DEFAULT : settings;
        this.buffer = buffer == null ? new DubheEventClassBuffer(this.settings) : buffer;
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    /**
     * One aggregated payload as one line: every group's "count + name" joined by the language's separator, then
     * wrapped by the kind's own template.
     *
     * <p>The join happens here rather than in a template because a nested template chain would run into the renderer's
     * depth cap ("20 dirt and 60 stone" is already two levels). Rendering each group at depth one and joining the
     * results keeps the wording in the language table without ever nesting deeper.</p>
     *
     * @return {@code false} when nothing could be worded, so the caller can fall back to its own diagnostics
     */
    public boolean submitBatch(String sourceId, DubhePresenceEventMapper.Batch batch, long nowMillis) {
        if (closed || batch == null) {
            return false;
        }
        if (batch.parts().isEmpty()) {
            // A detail-less kind ("opened a container", "picked something up"): the kind's own template is the
            // whole sentence.
            String line = DubheEventTexts.sanitize(texts.text(batch.lineKey()));
            if (line.isBlank()) {
                return false;
            }
            land(DubheEventDescriptor.of(batch.kind(), sourceId,
                    DubheEventPayload.of(batch.totalCount(), null), DubheEventText.of(batch.lineKey())), line, nowMillis);
            return true;
        }
        String separator = texts.text(BATCH_JOIN_KEY);
        StringBuilder joined = new StringBuilder();
        boolean shortened = false;
        for (DubheEventText part : batch.parts()) {
            String fragment = DubheEventTexts.sanitize(texts.render(part));
            if (fragment.isBlank()) {
                continue;
            }
            String candidate = joined.length() == 0 ? fragment : joined + separator + fragment;
            if (candidate.length() > MAX_BATCH_CHARS) {
                // Shorten here rather than letting the sanitizer cut mid-sentence later: the suffix says out loud
                // that more happened, which a silent cut never does.
                shortened = true;
                break;
            }
            joined.setLength(0);
            joined.append(candidate);
        }
        if (shortened) {
            joined.append(texts.text(batch.moreKey()));
        }
        if (joined.length() == 0) {
            return false;
        }
        String sentence = DubheEventTexts.sanitize(texts.text(batch.lineKey(), joined.toString()));
        if (sentence.isBlank()) {
            return false;
        }
        land(DubheEventDescriptor.of(batch.kind(), sourceId,
                DubheEventPayload.of(batch.totalCount(), null), DubheEventText.of(batch.lineKey())), sentence, nowMillis);
        return true;
    }

    /** Submits one event stamped with the wall clock; the call any module makes. */
    public void submit(DubheEventDescriptor descriptor) {
        submit(descriptor, System.currentTimeMillis());
    }

    /**
     * Submits one event decided at an explicit clock reading.
     *
     * @param nowMillis monotonic wall clock of the calling tick
     */
    public void submit(DubheEventDescriptor descriptor, long nowMillis) {
        if (closed || descriptor == null) {
            return;
        }
        land(descriptor, sentenceOf(descriptor), nowMillis);
    }

    /** {@link #submitRendered(String, String, DubheEventClass, String, long)} stamped with the wall clock. */
    public void submitRendered(String sourceId, String kind, DubheEventClass behavior, String text) {
        submitRendered(sourceId, kind, behavior, text, System.currentTimeMillis());
    }

    /**
     * Submits one event whose sentence the producer already rendered, under the behaviour it asked for.
     *
     * <p>Only the wording step differs from {@link #submit}: the sentence is sanitized here instead of being rendered
     * from a template. Everything after that — the frequency step, the attention list, the class, both holding areas,
     * the history line and the utterance — is the very path {@link #submit} takes, so a third-party event cannot
     * acquire a second, differently behaved entry into the layer.</p>
     *
     * <p>A requested behaviour is a request, not a decision: {@link DubheEventPolicy#tierFor} turns it into the one
     * declaration the table needs, and the frequency gate plus the attention list keep the right to move the result
     * down. {@link DubheEventClass#DROP} is rejected outright, as is a malformed kind — the protocol edge turns both
     * into a rejected envelope instead of letting them reach the decision path.</p>
     *
     * @param text the producer's own sentence; blank is ignored, exactly like an unrenderable template
     */
    public void submitRendered(String sourceId, String kind, DubheEventClass behavior, String text, long nowMillis) {
        if (closed) {
            return;
        }
        String sentence = DubheEventTexts.sanitize(text);
        if (sentence.isBlank()) {
            return;
        }
        DubheEventPolicy.Declaration declaration = DubheEventPolicy.declarationFor(behavior);
        DubheEventDescriptor descriptor = DubheEventDescriptor
                .rendered(kind, declaration.tier(), sourceId)
                .withHighlight(declaration.state() == DubheEventState.EMPHASIS)
                .withSuppress(declaration.state() == DubheEventState.WEAKENED);
        land(descriptor, sentence, nowMillis);
    }

    private void land(DubheEventDescriptor descriptor, String sentence, long nowMillis) {
        DubheEventDecision decision = dispatcher.decide(descriptor, sentence, nowMillis);
        switch (decision.eventClass()) {
            case DROP -> {
                // Nothing at all: no line, no utterance. The decision is still returned to the caller as a whole,
                // so a producer that logs its own submissions can see the drop.
            }
            case SILENT -> append(new DubheEventAppend.Single(descriptor.kind(), sentence));
            case FOLD -> {
                if (!decision.folded()) {
                    // No fold shape: one window-only line, still excluded from compaction.
                    append(new DubheEventAppend.Transient(descriptor.kind(), 1, sentence));
                }
            }
            case IMMEDIATE -> submitImmediate(descriptor, sentence, nowMillis);
            case DEFERRED -> {
                buffer.defer(descriptor.kind(), sentence, nowMillis);
                logSink.info("dubhe.shared.event.deferred kind=" + descriptor.kind()
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
        // The immediate queue only ever fills while a turn is running and the player does not allow interruptions,
        // so "not busy" means the turn that blocked those lines has ended: they are said now, merged into one
        // utterance, and each keeps its own history line. The deferred window is independent of this.
        if (!settings.busy()) {
            speakBatch("immediate_released", buffer.takeImmediate());
        }
        speakBatch("voluntary_turn", buffer.dueDeferred(nowMillis));
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
        for (DubheEventClassBuffer.Entry entry : buffer.takeForUserTurn()) {
            append(new DubheEventAppend.Single(entry.kind(), entry.text()));
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
        for (DubheEventAppend pending : dispatcher.flushAppends()) {
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
        DubheEventClock.clear(this);
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

    private void submitImmediate(DubheEventDescriptor descriptor, String sentence, long nowMillis) {
        if (!settings.busy() || settings.allowInterruption()) {
            speak(descriptor.kind(), sentence, "immediate");
            // The event happened and was said: the history has to carry the same line, or memory would only know about
            // the events that were too quiet to speak.
            append(new DubheEventAppend.Single(descriptor.kind(), sentence));
            return;
        }
        buffer.holdImmediate(descriptor.kind(), sentence, nowMillis);
        logSink.info("dubhe.shared.event.queued kind=" + descriptor.kind()
                + " source=" + descriptor.sourceId()
                + " queued=" + buffer.immediateSize());
    }

    /**
     * One batch spoken as a single utterance; every entry keeps its own history line.
     *
     * @param reason diagnostic name of the door the batch came through ({@code immediate_released} when the turn that
     *               held it ended, {@code voluntary_turn} when the deferred window closed)
     */
    private void speakBatch(String reason, List<DubheEventClassBuffer.Entry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (DubheEventClassBuffer.Entry entry : batch) {
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
        logSink.info("dubhe.shared.event." + reason + " kind=" + kind + " merged=" + batch.size());
        speechSink.speak(kind, joined.toString());
        // Each occurrence was its own event even though it was said in one breath, so the history keeps them apart.
        for (DubheEventClassBuffer.Entry entry : batch) {
            if (entry.text() != null && !entry.text().isBlank()) {
                append(new DubheEventAppend.Single(entry.kind(), entry.text()));
            }
        }
    }

    private void speak(String kind, String sentence, String reason) {
        // The utterance is the sentence this service rendered, passed through unchanged: the event layer holds no
        // wording of its own. The log line is the diagnostic; the sink is the voice channel.
        logSink.info("dubhe.shared.event.speech_pending kind=" + kind
                + " reason=" + reason
                + " text=" + sentence);
        speechSink.speak(kind, sentence);
    }

    private void drainFolds(long nowMillis) {
        for (DubheEventAppend due : dispatcher.drainDueAppends(nowMillis)) {
            append(due);
        }
    }

    /**
     * Renders one descriptor, falling back to the kind code when the template cannot be worded.
     *
     * <p>An empty line would take a history slot without carrying anything, which reads to the model as an event that
     * happened and said nothing — worse than a bare kind code, which at least names what happened.
     */
    private String sentenceOf(DubheEventDescriptor descriptor) {
        String rendered = DubheEventTexts.sanitize(texts.render(descriptor.text()));
        if (!rendered.isBlank()) {
            return rendered;
        }
        String key = descriptor.text().key();
        logSink.info(UNRESOLVED
                + " kind=" + descriptor.kind()
                + " source=" + descriptor.sourceId()
                + " key=" + key
                // A resolvable root whose result is still blank means the arguments failed: a missing nested key, or
                // nesting past DubheEventText.MAX_REF_DEPTH. Both report themselves the same way.
                + " reason=" + (texts.exists(key) ? "arguments" : "missing_key"));
        return descriptor.kind();
    }

    private void append(DubheEventAppend append) {
        if (append == null || historySink == null) {
            return;
        }
        historySink.append(append);
    }
}
