package com.rheinmetal.tianshu.dubhe.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.producer.DubheGameEventTextProvider;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputSettings;
import com.rheinmetal.tianshu.protocol.Priority;

/**
 * Speaks an {@link DubheSpeechLine} on the TTS capability that already exists.
 *
 * <p>No new voice protocol: the sentence is submitted through the module's {@link DubheSpeechSink}, which is
 * either the bridge to the speech module or the inert stand-in when that module is not installed. Queuing
 * is therefore its job — this class only picks <em>where</em> in the queue the line joins, and never asks
 * for an interruption.</p>
 *
 * <p>Keyed lines are resolved here, on the platform side, through the client language table
 * ({@link DubheGameEventTextProvider}). A key that does not resolve is a diagnostic, not an exception and
 * not a line of text: an unresolved key must never be spoken, and it must never stop a delegation from
 * finishing.</p>
 */
public final class DubheProtocolSpeechOutlet implements DubheSpeechOutlet {

    private static final String DISABLED = "ax.speech.disabled";
    private static final String UNRESOLVED = "ax.speech.unresolved";
    private static final String FAILED = "ax.speech.failed";

    private final DubheSpeechSink sink;
    private final DubheGameEventTextProvider texts;
    private final DubheOutputSettings settings;
    private final LogSink logs;

    public DubheProtocolSpeechOutlet(
            DubheSpeechSink sink,
            DubheGameEventTextProvider texts,
            DubheOutputSettings settings,
            LogSink logs) {
        this.sink = sink == null ? DubheSpeechSink.NONE : sink;
        this.texts = texts == null ? DubheGameEventTextProvider.NONE : texts;
        this.settings = settings == null ? DubheOutputSettings.DEFAULT : settings;
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    @Override
    public void speak(DubheSpeechLine line) {
        if (line == null) {
            return;
        }
        if (!settings.ttsEnabled()) {
            // Voice switched off (or never wired): the line is reported and dropped. Nothing upstream
            // learns about it, which is what keeps a silent host from failing the delegation.
            logs.info(DISABLED + " source=" + line.source().name()
                    + (line.isKeyed() ? " key=" + line.key() : ""));
            return;
        }
        String sentence = render(line);
        if (sentence.isBlank()) {
            logs.warn(UNRESOLVED + " source=" + line.source().name() + " key=" + line.key());
            return;
        }
        try {
            sink.speak(DubheSpeechUtterance.command(sentence, placementOf(line.priority()),
                    protocolPriorityOf(line.priority()), settings.voiceProfile()));
        } catch (RuntimeException unavailable) {
            logs.error(FAILED + " source=" + line.source().name(), unavailable);
        }
    }

    private String render(DubheSpeechLine line) {
        if (!line.isKeyed()) {
            return line.text();
        }
        if (!texts.exists(line.key())) {
            return "";
        }
        return texts.text(line.key(), line.arguments().toArray());
    }

    /** Queue spots that never interrupt: a receipt joins the session, a conclusion joins mid-queue. */
    private static DubheSpeechUtterance.Placement placementOf(DubheSpeechPriority priority) {
        return priority == DubheSpeechPriority.HIGH
                ? DubheSpeechUtterance.Placement.AFTER_SENTENCE
                : DubheSpeechUtterance.Placement.AFTER_SESSION;
    }

    private static Priority protocolPriorityOf(DubheSpeechPriority priority) {
        return priority == DubheSpeechPriority.HIGH ? Priority.HIGH : Priority.NORMAL;
    }
}
