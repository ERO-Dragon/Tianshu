package com.rheinmetal.tianshu.dubhe.agent.loop;

import com.rheinmetal.tianshu.dubhe.DubheTurnCancellation;
import com.rheinmetal.tianshu.weaving.contract.LLMPromptResultPayload;
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
class DubheStepSignalTest {

    private static final LLMPromptResultPayload PAYLOAD = LLMPromptResultPayload.completed("request", "answer");

    @Test
    void signalArrivingBeforeAwaitIsNotLost() throws Exception {
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();
        // A provider may answer inside submit(), i.e. before anybody awaits (§12.2 discipline 2).
        step.complete(new DubheStepOutcome.Finished<>(PAYLOAD));

        DubheStepOutcome<LLMPromptResultPayload> signal = step.await(1_000L, DubheRunCancellation.none());

        assertTrue(signal instanceof DubheStepOutcome.Finished<?>);
        assertSame(PAYLOAD, ((DubheStepOutcome.Finished<?>) signal).result());
        assertEquals(0, step.pollRescuedCount(), "an already delivered signal is not a poll rescue");
    }

    @Test
    void awaitGivesUpWhenTheLoopBudgetIsSpent() {
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();

        assertThrows(TimeoutException.class, () -> step.await(30L, DubheRunCancellation.none()));
    }

    @Test
    void firstSignalWinsAndLaterOnesAreIgnored() throws Exception {
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();
        DubheTurnCancellation reason = DubheTurnCancellation.playerInterrupted("player spoke again");

        step.complete(new DubheStepOutcome.Aborted<>(reason, DubheRunExpiry.NONE));
        step.complete(new DubheStepOutcome.Finished<>(PAYLOAD));

        DubheStepOutcome<LLMPromptResultPayload> signal = step.await(1_000L, DubheRunCancellation.none());
        assertTrue(signal instanceof DubheStepOutcome.Aborted<?>);
        assertSame(reason, ((DubheStepOutcome.Aborted<?>) signal).cancellation());
    }

    @Test
    void pollWakesTheRunWhenNoEventArrives() throws Exception {
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();
        DubheTurnCancellation reason = DubheTurnCancellation.moduleUnloaded("module stopped");
        DubheRunCancellation cancellation = DubheRunCancellation.of(() -> true, () -> reason);

        long startedAt = System.nanoTime();
        DubheStepOutcome<LLMPromptResultPayload> signal = step.await(5_000L, cancellation);
        long waitedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertTrue(signal instanceof DubheStepOutcome.Aborted<?>, "cancellation must end the wait");
        assertSame(reason, ((DubheStepOutcome.Aborted<?>) signal).cancellation());
        assertEquals(DubheRunExpiry.NONE, ((DubheStepOutcome.Aborted<?>) signal).expiry());
        assertEquals(1, step.pollRescuedCount(), "the fallback counter must record the rescue");
        assertTrue(waitedMillis < 5_000L, "the fallback must not wait for the whole budget");
    }

    @Test
    void eventWakeIsNotCountedAsAPollRescue() throws Exception {
        DubheStepSignal<LLMPromptResultPayload> step = new DubheStepSignal<>();
        Thread signaller = new Thread(() -> {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            step.complete(new DubheStepOutcome.Finished<>(PAYLOAD));
        }, "ax-step-signal");
        signaller.setDaemon(true);
        signaller.start();

        DubheStepOutcome<LLMPromptResultPayload> signal = step.await(5_000L, DubheRunCancellation.none());
        signaller.join(1_000L);

        assertTrue(signal instanceof DubheStepOutcome.Finished<?>);
        assertEquals(0, step.pollRescuedCount(), "an event-driven wake-up must not inflate the fallback counter");
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
