package com.rheinmetal.tianshu.function.auxilium.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two holding areas: queue semantics, the deferred window, and the arrival order a user turn inherits.
 *
 * <p>The jitter is injected, never asserted as a concrete value: what matters is that the window lands inside
 * {@code [base, base + jitter)} and that it is drawn again after each window, because a fixed beat is exactly what the
 * random part exists to avoid.
 */
class AXEventClassBufferTest {
    private static final long START = 1_000_000L;
    private static final long BASE = AXEventSettings.DEFAULT_DEFERRED_COOLDOWN_BASE_MILLIS;
    private static final long JITTER = AXEventSettings.DEFAULT_DEFERRED_COOLDOWN_JITTER_MILLIS;

    @Test
    void theCooldownLandsInsideTheConfiguredRange() {
        AXEventClassBuffer buffer = new AXEventClassBuffer(AXEventSettings.DEFAULT, () -> 42_000L);

        buffer.defer("tianshu:death", "line", START);

        long window = buffer.nextDeferredAtMillis() - START;
        assertEquals(BASE + 42_000L, window);
        assertTrue(window >= BASE && window < BASE + JITTER, "window must stay inside [120s, 180s)");
    }

    @Test
    void everyWindowIsDrawnAgain() {
        long[] draws = {0L, 59_999L};
        AtomicInteger index = new AtomicInteger();
        AXEventClassBuffer buffer = new AXEventClassBuffer(AXEventSettings.DEFAULT, () -> draws[index.getAndIncrement()]);

        buffer.defer("tianshu:death", "first", START);
        long firstDeadline = buffer.nextDeferredAtMillis();
        assertEquals(BASE, firstDeadline - START);

        List<AXEventClassBuffer.Entry> first = buffer.dueDeferred(firstDeadline);
        assertEquals(1, first.size(), "the single buffered entry is due");
        long secondDeadline = buffer.nextDeferredAtMillis();

        assertEquals(BASE + 59_999L, secondDeadline - firstDeadline, "the next window is a fresh draw");
        assertTrue(secondDeadline - firstDeadline >= BASE && secondDeadline - firstDeadline < BASE + JITTER);
    }

    @Test
    void theDeferredBatchIsOnlyReleasedAfterTheWindow() {
        AXEventClassBuffer buffer = new AXEventClassBuffer(AXEventSettings.DEFAULT, () -> 0L);
        buffer.defer("tianshu:death", "first", START);
        buffer.defer("tianshu:death", "second", START + 1_000L);

        assertTrue(buffer.dueDeferred(START + BASE - 1L).isEmpty(), "one millisecond early is still closed");
        List<AXEventClassBuffer.Entry> batch = buffer.dueDeferred(START + BASE);

        assertEquals(List.of("first", "second"), texts(batch));
        assertEquals(0, buffer.deferredSize(), "the batch leaves the buffer");
    }

    @Test
    void aUserTurnDrainsBothAreasInArrivalOrder() {
        AXEventClassBuffer buffer = new AXEventClassBuffer(AXEventSettings.DEFAULT, () -> 0L);
        buffer.holdImmediate("tianshu:death", "one", START);
        buffer.defer("tianshu:respawn", "two", START + 10L);
        buffer.holdImmediate("tianshu:death", "three", START + 20L);

        assertEquals(List.of("one", "two", "three"), texts(buffer.takeForUserTurn()));
        assertEquals(0, buffer.immediateSize());
        assertEquals(0, buffer.deferredSize());
        assertTrue(buffer.takeForUserTurn().isEmpty(), "a second turn has nothing left to drain");
    }

    @Test
    void aUserTurnRestartsTheDeferredWindow() {
        AXEventClassBuffer buffer = new AXEventClassBuffer(AXEventSettings.DEFAULT, () -> 0L);
        buffer.defer("tianshu:death", "first", START);
        buffer.takeForUserTurn();

        assertEquals(Long.MIN_VALUE, buffer.nextDeferredAtMillis(), "nothing is armed after a user turn");

        buffer.defer("tianshu:death", "second", START + 5_000L);
        assertEquals(START + 5_000L + BASE, buffer.nextDeferredAtMillis(), "the next entry starts a fresh window");
    }

    private static List<String> texts(List<AXEventClassBuffer.Entry> entries) {
        List<String> texts = new ArrayList<>(entries.size());
        for (AXEventClassBuffer.Entry entry : entries) {
            texts.add(entry.text());
        }
        return texts;
    }
}
