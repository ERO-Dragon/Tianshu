package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolTopics;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.EmptyPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store keeps history for cancellation and trace queries; these tests pin the two rules that keep it
 * from growing forever without ever dropping something still in flight.
 */
class EnvelopeRetentionTest {

    private final EnvelopeLifecycleStore store = new EnvelopeLifecycleStore();

    private static TianshuEnvelope envelope(String id, String traceId, long createdAt) {
        return EnvelopeBuilder.eventTopic("test", ProtocolTopics.WORLD_EVENT, PayloadType.CUSTOM, new EmptyPayload())
                .envelopeId(id)
                .traceId(traceId)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void finishedTracesOlderThanTheWindowAreDropped() {
        long now = System.currentTimeMillis();
        store.accept(envelope("old", "trace-old", now - 60_000L));
        store.transition("old", EnvelopeStatus.COMPLETED, "COMPLETED", "");

        assertEquals(1, store.allEnvelopes().size());
        int dropped = store.cleanupTerminalTraces(30_000L, 100);

        assertEquals(1, dropped);
        assertTrue(store.allEnvelopes().isEmpty());
    }

    @Test
    void aTraceWithWorkInFlightIsNeverDropped() {
        long now = System.currentTimeMillis();
        store.accept(envelope("running", "trace-running", now - 60_000L));
        store.transition("running", EnvelopeStatus.RUNNING, "RUNNING", "");

        // Age says "drop everything", the cap says "keep one": the in-flight trace still stays.
        store.cleanupTerminalTraces(-1L, 1);

        assertTrue(store.findEnvelope("running").isPresent());
    }

    @Test
    void theCapKeepsTheNewestFinishedTraces() {
        long now = System.currentTimeMillis();
        for (int index = 0; index < 5; index++) {
            String traceId = "trace-" + index;
            store.accept(envelope("e" + index, traceId, now - (5L - index) * 1_000L));
            store.transition("e" + index, EnvelopeStatus.COMPLETED, "COMPLETED", "");
        }

        store.cleanupTerminalTraces(Long.MAX_VALUE, 2);

        assertFalse(store.findEnvelope("e0").isPresent());
        assertFalse(store.findEnvelope("e1").isPresent());
        assertFalse(store.findEnvelope("e2").isPresent());
        assertTrue(store.findEnvelope("e3").isPresent());
        assertTrue(store.findEnvelope("e4").isPresent());
    }

    @Test
    void aDroppedChildLeavesNoParentIndexEntry() {
        long createdAt = System.currentTimeMillis() - 60_000L;
        TianshuEnvelope parent = envelope("parent", "trace-parent", createdAt);
        TianshuEnvelope child = EnvelopeBuilder
                .eventTopic("test", ProtocolTopics.WORLD_EVENT, PayloadType.CUSTOM, new EmptyPayload())
                .envelopeId("child")
                .traceId("trace-parent")
                .parentId(parent.envelopeId())
                .createdAt(createdAt)
                .build();
        store.accept(parent);
        store.accept(child);
        store.transition("parent", EnvelopeStatus.COMPLETED, "COMPLETED", "");
        store.transition("child", EnvelopeStatus.COMPLETED, "COMPLETED", "");

        store.cleanupTerminalTraces(1_000L, 100);

        assertTrue(store.childrenOf("parent").isEmpty());
        assertTrue(store.allEnvelopes().isEmpty());
    }

    @Test
    void theRuntimeSweepsWhileItKeepsAccepting() {
        ProtocolRuntime runtime = ProtocolBootstrap.create(
                Runnable::run,
                ProtocolRuntimePolicy.builder()
                        .retentionSweepInterval(64)
                        .retainedEnvelopeMillis(60_000L)
                        .maxRetainedTraces(64)
                        .build()
        );
        try {
            for (int index = 0; index < 400; index++) {
                runtime.submit(EnvelopeBuilder
                        .eventTopic("host", ProtocolTopics.WORLD_EVENT, PayloadType.CUSTOM, new EmptyPayload())
                        .build());
            }
            int kept = runtime.lifecycle().allEnvelopes().size();
            assertTrue(kept > 0, "the newest history must stay queryable");
            assertTrue(kept <= 64 + 64, "retention must bound the store, kept=" + kept);
        } finally {
            runtime.close();
        }
    }
}
