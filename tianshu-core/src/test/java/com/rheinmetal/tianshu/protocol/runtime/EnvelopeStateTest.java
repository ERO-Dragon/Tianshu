package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.EmptyPayload;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The envelope state machine and the listeners hanging off it.
 *
 * <p>Two things used to be unguarded: a terminal state could be overwritten by whatever arrived late, and a
 * cancellation callback was never dropped, so it kept the registering module reachable for the session and one
 * throwing callback skipped the rest.</p>
 */
class EnvelopeStateTest {

    private static TianshuEnvelope envelope() {
        return EnvelopeBuilder.create()
                .sourceId("test")
                .target("test.capability")
                .payload(new EmptyPayload())
                .build();
    }

    @Test
    void aTerminalStateCannotBeRewritten() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        TianshuEnvelope envelope = envelope();
        store.accept(envelope);
        store.transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "DONE", "");

        store.transition(envelope.envelopeId(), EnvelopeStatus.FAILED, "LATE", "a late failure");

        assertEquals(EnvelopeStatus.COMPLETED, store.statusOf(envelope.envelopeId()),
                "a completed envelope must not turn into a failed one");
    }

    @Test
    void aCancellationCallbackFiresOnceAndIsThenDropped() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        CancellationRegistry registry = new CancellationRegistry(store);
        TianshuEnvelope envelope = envelope();
        store.accept(envelope);
        List<String> seen = new ArrayList<>();
        registry.onCancel(envelope.envelopeId(), ignored -> seen.add("first"));

        registry.cancelSelf(envelope.envelopeId(), "TEST", "cancelled");
        registry.cancelSelf(envelope.envelopeId(), "TEST", "cancelled again");

        assertEquals(List.of("first"), seen, "the callback runs once and is not retained afterwards");
    }

    @Test
    void oneThrowingCallbackDoesNotStopTheOthers() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        CancellationRegistry registry = new CancellationRegistry(store);
        TianshuEnvelope envelope = envelope();
        store.accept(envelope);
        List<String> seen = new ArrayList<>();
        registry.onCancel(envelope.envelopeId(), ignored -> {
            throw new IllegalStateException("a consumer bug");
        });
        registry.onCancel(envelope.envelopeId(), ignored -> seen.add("second"));

        registry.cancelSelf(envelope.envelopeId(), "TEST", "cancelled");

        assertEquals(List.of("second"), seen, "the second consumer must still be told");
    }
}