package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.protocol.DeadLetterPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.EmptyPayload;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failure has to leave a trace, not just a status field.
 *
 * <p>The whole protocol runtime used to report nowhere: a handler could throw, an envelope could be dead-lettered,
 * and the only artefact left was a {@code FAILED} string that had already dropped the throwable. That made the
 * intermittent failures this queue is meant to prevent impossible to diagnose after the fact.</p>
 */
class ProtocolFailureLoggingTest {

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private final LogSink recorder = new LogSink() {
        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message, Throwable failure) {
            errors.add(message + " | cause=" + failure);
        }
    };

    @Test
    void aHandlerFailureIsReportedWithItsCause() {
        ProtocolRuntime runtime = new ProtocolRuntime(
                runnable -> runnable.run(), new TriggerRegistry(), ProtocolRuntimePolicy.defaults(), recorder);
        TianshuEnvelope envelope = EnvelopeBuilder.create()
                .sourceId("test")
                .target("test.capability")
                .payload(new EmptyPayload())
                .build();

        runtime.handleFailure(envelope, "HANDLER_EXCEPTION", "the handler exploded",
                new IllegalStateException("the handler exploded"));

        assertTrue(errors.stream().anyMatch(line -> line.contains(envelope.envelopeId())
                        && line.contains("HANDLER_EXCEPTION")
                        && line.contains("the handler exploded")),
                "the report must name the envelope, the code and the cause: " + errors);
    }

    @Test
    void aDeadLetterIsReportedWhileItsReasonStillExists() {
        DeadLetterQueue queue = new DeadLetterQueue(32, new EnvelopeLifecycleStore(), recorder);
        TianshuEnvelope envelope = EnvelopeBuilder.create()
                .sourceId("test")
                .target("test.capability")
                .payload(new EmptyPayload())
                .build();

        queue.add(envelope, "BROKER_QUEUE_FULL", "the queue is full", DeadLetterPolicy.LOG_ONLY);

        assertTrue(warnings.stream().anyMatch(line -> line.contains(envelope.envelopeId())
                        && line.contains("BROKER_QUEUE_FULL")
                        && line.contains("the queue is full")),
                "a dropped envelope must say why before the reason rotates out of the queue: " + warnings);
    }
}