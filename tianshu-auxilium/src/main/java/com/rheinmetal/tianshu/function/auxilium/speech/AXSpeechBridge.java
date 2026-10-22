package com.rheinmetal.tianshu.function.auxilium.speech;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDelivery;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateDeliverySink;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventSpeechSink;

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
public final class AXSpeechBridge implements AXDelegateDeliverySink, AXEventSpeechSink {

    private static final String SKIPPED = "ax.speech.skipped";
    private static final String FAILED = "ax.speech.failed";

    private final AXSpeechOutlet outlet;
    private final LogSink logs;

    public AXSpeechBridge(AXSpeechOutlet outlet, LogSink logs) {
        this.outlet = outlet == null ? AXSpeechOutlet.NONE : outlet;
        this.logs = logs == null ? LogSink.NOOP : logs;
    }

    @Override
    public void publish(AXDelegateDelivery delivery) {
        if (delivery == null) {
            return;
        }
        if (delivery.utteranceKey() != null && !delivery.utteranceKey().isBlank()) {
            speak(AXSpeechLine.keyed(
                    AXSpeechSource.DELEGATE,
                    delivery.isAck() ? AXSpeechPriority.NORMAL : AXSpeechPriority.HIGH,
                    delivery.utteranceKey(),
                    List.of()));
            return;
        }
        if (delivery.text().isBlank()) {
            logs.info(SKIPPED + " source=" + AXSpeechSource.DELEGATE.name() + " task=" + delivery.taskId());
            return;
        }
        speak(AXSpeechLine.literal(AXSpeechSource.DELEGATE, AXSpeechPriority.HIGH, delivery.text()));
    }

    @Override
    public void speak(String kind, String text) {
        if (text == null || text.isBlank()) {
            logs.info(SKIPPED + " source=" + AXSpeechSource.EVENT.name() + " kind=" + kind);
            return;
        }
        speak(AXSpeechLine.literal(AXSpeechSource.EVENT, AXSpeechPriority.HIGH, text));
    }

    private void speak(AXSpeechLine line) {
        try {
            outlet.speak(line);
        } catch (RuntimeException unavailable) {
            logs.error(FAILED + " source=" + line.source().name(), unavailable);
        }
    }
}
