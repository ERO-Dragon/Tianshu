package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.function.presence.capture.PresenceWorldEventAggregator;
import com.rheinmetal.tianshu.protocol.BrokerType;
import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.adapter.AdapterDefaults;
import com.rheinmetal.tianshu.function.presence.payload.PresenceWorldEventPayload;
import com.rheinmetal.tianshu.function.presence.protocol.PresenceTopics;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.registry.ModuleDescriptor;
import com.rheinmetal.tianshu.protocol.registry.TopicSubscriptionDescriptor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves presence's own sending path end to end: facts in, one windowed payload out on the wire topic.
 *
 * <p>The socket-to-topic half is exercised with the real protocol runtime, so this covers the aggregator, the
 * collector and the adapter's publish call in one go.</p>
 */
class PresenceWorldEventPublishingTest {

    @Test
    void aSecondOfFactsLeavesAsOnePayloadWithEveryGroup() {
        try (ProtocolRuntime runtime = ProtocolBootstrap.create(Runnable::run)) {
            List<PresenceWorldEventPayload> received = captureWorldEvents(runtime);
            PresenceProtocolAdapter adapter = new PresenceProtocolAdapter(runtime);
            adapter.registerOwnedTopics(runtime);

            AtomicLong now = new AtomicLong(1_000L);
            PresenceEventCollector collector = new PresenceEventCollector(
                    LogSink.NOOP,
                    new PresenceWorldEventAggregator(now::get)
            );
            collector.setWorldEventSink(adapter::publishWorldEvent);
            collector.startWorldSession();

            collector.recordWorldEvent(pickup("minecraft:dirt", "泥土"));
            collector.recordWorldEvent(pickup("minecraft:dirt", "泥土"));
            collector.recordWorldEvent(pickup("minecraft:stone", "石头"));
            now.set(2_000L);
            collector.flushWorldEvents();

            awaitSize(received, 1);
            PresenceWorldEventPayload payload = received.get(0);
            assertEquals(PresenceWorldEventPayload.EVENT_ITEM_PICKUP, payload.eventType());
            assertEquals(2, payload.occurrences().size());
            assertEquals(3, payload.totalCount());
            assertEquals("泥土", payload.occurrences().get(0).values()
                    .get(PresenceWorldEventPayload.VALUE_ITEM_NAME));
            assertEquals(2, payload.occurrences().get(0).count());
        }
    }

    private static WorldEventPayload pickup(String itemId, String itemName) {
        return new WorldEventPayload(
                PresenceWorldEventPayload.EVENT_ITEM_PICKUP,
                "player",
                "minecraft:overworld",
                Map.of(
                        PresenceWorldEventPayload.VALUE_ITEM_ID, itemId,
                        PresenceWorldEventPayload.VALUE_ITEM_NAME, itemName
                ),
                1_000L
        );
    }

    private static List<PresenceWorldEventPayload> captureWorldEvents(ProtocolRuntime runtime) {
        List<PresenceWorldEventPayload> received = new CopyOnWriteArrayList<>();
        AdapterDefaults defaults = AdapterDefaults.standard();
        runtime.subscribeTopic(
                new ModuleDescriptor(
                        "module.presence.event-test", List.of(), defaults.threadPolicy(),
                        defaults.cancellationScope(), defaults.failurePolicy(), defaults.deliveryPolicy(),
                        defaults.cancellable(), defaults.supportsStreaming(), 1, defaults.queueCapacity()
                ),
                new TopicSubscriptionDescriptor(
                        PresenceTopics.WORLD_EVENT,
                        PayloadType.CUSTOM,
                        PresenceWorldEventPayload.class,
                        BrokerType.BOUNDED_QUEUE,
                        EnumSet.of(PacketType.EVENT),
                        Priority.LOW,
                        CompletionPolicy.AUTO_COMPLETE_ON_RETURN
                ),
                (envelope, context) -> received.add((PresenceWorldEventPayload) envelope.payload())
        );
        return received;
    }

    private static void awaitSize(List<?> values, int expected) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (values.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        if (values.size() < expected) {
            fail("expected " + expected + " world events, received " + values.size());
        }
    }
}
