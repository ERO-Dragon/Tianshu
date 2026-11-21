package com.rheinmetal.tianshu.dubhe.dialogue.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateDelivery;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegateDeliverySink;
import com.rheinmetal.tianshu.dubhe.shared.event.DubheEventSpeechSink;

import java.util.List;

/**
 * The adapter between the shared producers and the voice boundary.
 *
 * <p>It implements both shared speech sinks — the delegation one and the event one — so the AX module
 * has exactly one voice channel instead of two parallel paths. Everything it does is translation:</p>
 * <ul>
 *   <li>a delegation line is keyed when it carries a resource key (receipt, failure, timeout,
 *       cancellation) and literal when it carries the conclusion text;</li>
 *   <li>an event line is always literal, because the producer already rendered it.</li>
 * </ul>
 *
 * <p>Empty content is dropped silently instead of being spoken: a blank sentence means the producer had
 * nothing to say, and an empty utterance would only make the TTS queue stutter. Failures of the outlet
 * are swallowed into the diagnostic log — speech must never fail a delegation, a turn or an event.</p>
 */
public final class DubheSpeechBridge implements DubheDelegateDeliverySink, DubheEventSpeechSink {

    private static final String SKIPPED = "dubhe.dialogue.speech.skipped";
    private static final String FAILED = "dubhe.dialogue.speech.failed";

    private final DubheSpeechOutlet outlet;
    private final LogSink logs;

    public DubheSpeechBridge(DubheSpeechOutlet outlet, LogSink logs) {
        this.outlet = outlet == null ? DubheSpeechOutlet.NONE : outlet;
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    @Override
    public void publish(DubheDelegateDelivery delivery) {
        if (delivery == null) {
            return;
        }
        if (delivery.utteranceKey() != null && !delivery.utteranceKey().isBlank()) {
            speak(DubheSpeechLine.keyed(
                    DubheSpeechSource.DELEGATE,
                    delivery.isAck() ? DubheSpeechPriority.NORMAL : DubheSpeechPriority.HIGH,
                    delivery.utteranceKey(),
                    List.of()));
            return;
        }
        if (delivery.text().isBlank()) {
            logs.info(SKIPPED + " source=" + DubheSpeechSource.DELEGATE.name() + " task=" + delivery.taskId());
            return;
        }
        speak(DubheSpeechLine.literal(DubheSpeechSource.DELEGATE, DubheSpeechPriority.HIGH, delivery.text()));
    }

    @Override
    public void speak(String kind, String text) {
        if (text == null || text.isBlank()) {
            logs.info(SKIPPED + " source=" + DubheSpeechSource.EVENT.name() + " kind=" + kind);
            return;
        }
        speak(DubheSpeechLine.literal(DubheSpeechSource.EVENT, DubheSpeechPriority.HIGH, text));
    }

    private void speak(DubheSpeechLine line) {
        try {
            outlet.speak(line);
        } catch (RuntimeException unavailable) {
            logs.error(FAILED + " source=" + line.source().name(), unavailable);
        }
    }
}
