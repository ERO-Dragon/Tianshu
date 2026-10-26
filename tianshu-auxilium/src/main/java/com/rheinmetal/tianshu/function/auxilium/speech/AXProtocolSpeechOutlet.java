package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventTextProvider;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.protocol.Priority;

/**
 * Speaks an {@link AXSpeechLine} on the TTS capability that already exists.
 *
 * <p>No new voice protocol: the sentence is submitted through the module's {@link AXSpeechSink}, which is
 * either the bridge to the speech module or the inert stand-in when that module is not installed. Queuing
 * is therefore its job 鈥?this class only picks <em>where</em> in the queue the line joins, and never asks
 * for an interruption.</p>
 *
 * <p>Keyed lines are resolved here, on the platform side, through the client language table
 * ({@link AXGameEventTextProvider}). A key that does not resolve is a diagnostic, not an exception and
 * not a line of text: an unresolved key must never be spoken, and it must never stop a delegation from
 * finishing.</p>
 */
public final class AXProtocolSpeechOutlet implements AXSpeechOutlet {

    private static final String DISABLED = "ax.speech.disabled";
    private static final String UNRESOLVED = "ax.speech.unresolved";
    private static final String FAILED = "ax.speech.failed";

    private final AXSpeechSink sink;
    private final AXGameEventTextProvider texts;
    private final AuxiliumOutputSettings settings;
    private final LogSink logs;

    public AXProtocolSpeechOutlet(
            AXSpeechSink sink,
            AXGameEventTextProvider texts,
            AuxiliumOutputSettings settings,
            LogSink logs) {
        this.sink = sink == null ? AXSpeechSink.NONE : sink;
        this.texts = texts == null ? AXGameEventTextProvider.NONE : texts;
        this.settings = settings == null ? AuxiliumOutputSettings.DEFAULT : settings;
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    @Override
    public void speak(AXSpeechLine line) {
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
            sink.speak(AXSpeechUtterance.command(sentence, placementOf(line.priority()),
                    protocolPriorityOf(line.priority()), settings.voiceProfile()));
        } catch (RuntimeException unavailable) {
            logs.error(FAILED + " source=" + line.source().name(), unavailable);
        }
    }

    private String render(AXSpeechLine line) {
        if (!line.isKeyed()) {
            return line.text();
        }
        if (!texts.exists(line.key())) {
            return "";
        }
        return texts.text(line.key(), line.arguments().toArray());
    }

    /** Queue spots that never interrupt: a receipt joins the session, a conclusion joins mid-queue. */
    private static AXSpeechUtterance.Placement placementOf(AXSpeechPriority priority) {
        return priority == AXSpeechPriority.HIGH
                ? AXSpeechUtterance.Placement.AFTER_SENTENCE
                : AXSpeechUtterance.Placement.AFTER_SESSION;
    }

    private static Priority protocolPriorityOf(AXSpeechPriority priority) {
        return priority == AXSpeechPriority.HIGH ? Priority.HIGH : Priority.NORMAL;
    }
}
