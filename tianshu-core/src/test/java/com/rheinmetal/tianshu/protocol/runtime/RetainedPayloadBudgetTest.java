package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.ITianshuPayload;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.SizedPayload;
import com.rheinmetal.tianshu.protocol.ThreadPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The history keeps traces inspectable without keeping every byte of them.
 *
 * <p>A synthesized sentence is hundreds of kilobytes. Thirty minutes of them is a memory budget nobody chose, and the
 * age rule alone cannot see it — it counts envelopes, not bytes.</p>
 */
class RetainedPayloadBudgetTest {

    @Test
    void oldPayloadsAreDroppedButTheirEnvelopesStay() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        for (int index = 0; index < 4; index++) {
            accepted(store, audioEnvelope("audio-" + index, 48_000));
        }
        assertEquals(4 * 48_000L, store.retainedPayloadBytes());

        store.cleanupTerminalTraces(1_000_000L, 8192, 96_000L);

        assertTrue(store.retainedPayloadBytes() <= 96_000L,
                "the byte budget must bound what is kept: " + store.retainedPayloadBytes());
        int keptBodies = 0;
        for (int index = 0; index < 4; index++) {
            var envelope = store.findEnvelope("audio-" + index).orElse(null);
            assertNotNull(envelope, "every envelope stays: a trace must remain inspectable and cancellable");
            assertEquals(EnvelopeStatus.COMPLETED, store.statusOf("audio-" + index));
            if (envelope.payload() != null) {
                keptBodies++;
            }
        }
        assertEquals(2, keptBodies, "the budget keeps the newest bodies it can and drops the rest");
    }

    @Test
    void aZeroBudgetMeansNoByteLimit() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        accepted(store, audioEnvelope("audio-only", 48_000));

        store.cleanupTerminalTraces(1_000_000L, 8192, 0L);

        assertEquals(48_000L, store.retainedPayloadBytes(), "zero is the explicit opt-out");
    }

    @Test
    void aPayloadThatDoesNotDeclareItsSizeIsLeftAlone() {
        EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();
        accepted(store, EnvelopeBuilder.commandToCapability("test.caller", "test.target", PayloadType.NONE,
                        new SmallPayload())
                .envelopeId("small")
                .threadPolicy(ThreadPolicy.ASYNC_WORKER)
                .build());

        store.cleanupTerminalTraces(1_000_000L, 8192, 1L);

        assertEquals(0L, store.retainedPayloadBytes());
        assertNotNull(store.findEnvelope("small").orElseThrow().payload(),
                "a payload with no size contract must not be treated as oversized");
    }

    /** An envelope that has already finished: the byte budget only applies to terminal history. */
    private static void accepted(EnvelopeLifecycleStore store,
                                 com.rheinmetal.tianshu.protocol.TianshuEnvelope envelope) {
        store.accept(envelope);
        store.transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "COMPLETED", "");
    }

    private static com.rheinmetal.tianshu.protocol.TianshuEnvelope audioEnvelope(String id, int bytes) {
        var envelope = EnvelopeBuilder.commandToCapability("test.caller", "test.target", PayloadType.NONE,
                        new AudioPayload(bytes))
                .envelopeId(id)
                .threadPolicy(ThreadPolicy.ASYNC_WORKER)
                .build();
        return envelope;
    }

    private record AudioPayload(int bytes) implements ITianshuPayload, SizedPayload {
        @Override
        public int retainedBytes() {
            return bytes;
        }
    }

    private record SmallPayload() implements ITianshuPayload {
    }
}