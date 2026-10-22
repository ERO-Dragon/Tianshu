package com.rheinmetal.tianshu.function.auxilium.core.agent;

import com.rheinmetal.tianshu.function.auxilium.AXTurnCancellation;
import com.rheinmetal.tianshu.protocol.payload.LLMPromptResultPayload;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-step signal slot shared by both run kinds (design §12.2 / §12.3).
 *
 * <p>These cases are deliberately about the slot itself — completeness of the wake-up protocol — so
 * they run in milliseconds instead of waiting for a real loop deadline.</p>
 */
class AXStepSignalTest {

    private static final LLMPromptResultPayload PAYLOAD = LLMPromptResultPayload.completed("request", "answer");

    @Test
    void signalArrivingBeforeAwaitIsNotLost() throws Exception {
        AXStepSignal<LLMPromptResultPayload> step = new AXStepSignal<>();
        // A provider may answer inside submit(), i.e. before anybody awaits (§12.2 discipline 2).
        step.complete(new AXStepOutcome.Finished<>(PAYLOAD));

        AXStepOutcome<LLMPromptResultPayload> signal = step.await(1_000L, AXRunCancellation.none());

        assertTrue(signal instanceof AXStepOutcome.Finished<?>);
        assertSame(PAYLOAD, ((AXStepOutcome.Finished<?>) signal).result());
        assertEquals(0, step.pollRescuedCount(), "an already delivered signal is not a poll rescue");
    }

    @Test
    void awaitGivesUpWhenTheLoopBudgetIsSpent() {
        AXStepSignal<LLMPromptResultPayload> step = new AXStepSignal<>();

        assertThrows(TimeoutException.class, () -> step.await(30L, AXRunCancellation.none()));
    }

    @Test
    void firstSignalWinsAndLaterOnesAreIgnored() throws Exception {
        AXStepSignal<LLMPromptResultPayload> step = new AXStepSignal<>();
        AXTurnCancellation reason = AXTurnCancellation.playerInterrupted("player spoke again");

        step.complete(new AXStepOutcome.Aborted<>(reason, AXRunExpiry.NONE));
        step.complete(new AXStepOutcome.Finished<>(PAYLOAD));

        AXStepOutcome<LLMPromptResultPayload> signal = step.await(1_000L, AXRunCancellation.none());
        assertTrue(signal instanceof AXStepOutcome.Aborted<?>);
        assertSame(reason, ((AXStepOutcome.Aborted<?>) signal).cancellation());
    }

    @Test
    void pollWakesTheRunWhenNoEventArrives() throws Exception {
        AXStepSignal<LLMPromptResultPayload> step = new AXStepSignal<>();
        AXTurnCancellation reason = AXTurnCancellation.moduleUnloaded("module stopped");
        AXRunCancellation cancellation = AXRunCancellation.of(() -> true, () -> reason);

        long startedAt = System.nanoTime();
        AXStepOutcome<LLMPromptResultPayload> signal = step.await(5_000L, cancellation);
        long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(signal instanceof AXStepOutcome.Aborted<?>, "cancellation must end the wait");
        assertSame(reason, ((AXStepOutcome.Aborted<?>) signal).cancellation());
        assertEquals(AXRunExpiry.NONE, ((AXStepOutcome.Aborted<?>) signal).expiry());
        assertEquals(1, step.pollRescuedCount(), "the fallback counter must record the rescue");
        assertTrue(waitedMillis < 5_000L, "the fallback must not wait for the whole budget");
    }

    @Test
    void eventWakeIsNotCountedAsAPollRescue() throws Exception {
        AXStepSignal<LLMPromptResultPayload> step = new AXStepSignal<>();
        Thread signaller = new Thread(() -> {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            step.complete(new AXStepOutcome.Finished<>(PAYLOAD));
        }, "ax-step-signal");
        signaller.setDaemon(true);
        signaller.start();

        AXStepOutcome<LLMPromptResultPayload> signal = step.await(5_000L, AXRunCancellation.none());
        signaller.join(1_000L);

        assertTrue(signal instanceof AXStepOutcome.Finished<?>);
        assertEquals(0, step.pollRescuedCount(), "an event-driven wake-up must not inflate the fallback counter");
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
