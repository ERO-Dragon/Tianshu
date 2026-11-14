package com.rheinmetal.tianshu.api.assembly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SignalFailuresTest {

    @BeforeEach
    @AfterEach
    void reset() {
        InputSignals.clear();
        TickSignals.clear();
        SignalFailures.resetForTests();
    }

    @Test
    void aThrowingInputSubscriberDoesNotReachThePublisherOrSilenceTheRest() {
        List<String> delivered = new ArrayList<>();
        InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, text -> {
            throw new IllegalStateException("module bug");
        });
        InputSignals.subscribe(InputSignals.TEXT_SUBMITTED, delivered::add);

        assertDoesNotThrow(() -> InputSignals.publish(InputSignals.TEXT_SUBMITTED, "hello"));

        assertEquals(List.of("hello"), delivered, "the subscriber after the failure must still run");
        assertEquals(1L, SignalFailures.failureCount());
    }

    @Test
    void aThrowingTickSubscriberIsContainedTheSameWay() {
        List<Long> delivered = new ArrayList<>();
        TickSignals.subscribe(TickSignals.TICK, now -> {
            throw new IllegalArgumentException("tick bug");
        });
        TickSignals.subscribe(TickSignals.TICK, delivered::add);

        assertDoesNotThrow(() -> TickSignals.publish(TickSignals.TICK, 123L));

        assertEquals(List.of(123L), delivered);
        assertEquals(1L, SignalFailures.failureCount());
    }

    @Test
    void theInstalledHandlerSeesTheBusChannelAndOriginalFailure() {
        AtomicReference<String> bus = new AtomicReference<>();
        AtomicReference<String> channel = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        SignalFailures.setHandler((reportedBus, reportedChannel, reportedFailure) -> {
            bus.set(reportedBus);
            channel.set(reportedChannel);
            failure.set(reportedFailure);
        });

        IllegalArgumentException original = new IllegalArgumentException("boom");
        InputSignals.subscribe("key.demo", text -> {
            throw original;
        });
        InputSignals.publish("key.demo", "");

        assertEquals("input", bus.get());
        assertEquals("key.demo", channel.get());
        assertSame(original, failure.get());
    }

    @Test
    void aThrowingHandlerDoesNotBreakTheBus() {
        SignalFailures.setHandler((reportedBus, reportedChannel, reportedFailure) -> {
            throw new IllegalStateException("reporter bug");
        });
        List<String> delivered = new ArrayList<>();
        InputSignals.subscribe(InputSignals.PUSH_TO_TALK_DOWN, text -> {
            throw new IllegalStateException("subscriber bug");
        });
        InputSignals.subscribe(InputSignals.PUSH_TO_TALK_DOWN, delivered::add);

        assertDoesNotThrow(() -> InputSignals.publish(InputSignals.PUSH_TO_TALK_DOWN, ""));

        assertEquals(1, delivered.size());
        assertEquals(1L, SignalFailures.failureCount());
    }
}
