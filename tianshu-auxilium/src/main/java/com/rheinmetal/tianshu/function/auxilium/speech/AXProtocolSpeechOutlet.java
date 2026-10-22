package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventTextProvider;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.protocol.voice.VoiceNotificationService;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;

/**
 * Speaks an {@link AXSpeechLine} on the TTS capability that already exists.
 *
 * <p>No new voice protocol: the sentence is submitted through {@link VoiceNotificationService}, which is
 * the one place the project already uses to say something outside a dialogue turn. Queuing is therefore
 * the TTS module's job — this class only picks <em>where</em> in the queue the line joins, and never
 * asks for an interruption.</p>
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

    private final VoiceNotificationService voice;
    private final AXGameEventTextProvider texts;
    private final AXOutputSettings settings;
    private final LogSink logs;

    public AXProtocolSpeechOutlet(
            VoiceNotificationService voice,
            AXGameEventTextProvider texts,
            AXOutputSettings settings,
            LogSink logs) {
        this.voice = voice;
        this.texts = texts == null ? AXGameEventTextProvider.NONE : texts;
        this.settings = settings == null ? AXOutputSettings.DEFAULT : settings;
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
            voice.speakQueued(sentence, placementOf(line.priority()), protocolPriorityOf(line.priority()));
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
    private static TtsPlaybackPlacement placementOf(AXSpeechPriority priority) {
        return priority == AXSpeechPriority.HIGH
                ? TtsPlaybackPlacement.INSERT_AFTER_SENTENCE
                : TtsPlaybackPlacement.INSERT_AFTER_SESSION;
    }

    private static Priority protocolPriorityOf(AXSpeechPriority priority) {
        return priority == AXSpeechPriority.HIGH ? Priority.HIGH : Priority.NORMAL;
    }
}
