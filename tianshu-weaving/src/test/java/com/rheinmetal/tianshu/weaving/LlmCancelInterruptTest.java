package com.rheinmetal.tianshu.weaving;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The interrupt a cancelled request raises must not outlive the request.
 *
 * <p>The lane hands the same thread the next envelope, so a pending interrupt left behind would abort
 * a request nobody cancelled. The guard exists to make that impossible while still letting a
 * cancellation stop a blocked provider stream.</p>
 */
class LlmCancelInterruptTest {

    @Test
    void theInterruptRaisedForACancelledRequestIsGoneWhenTheHandlerReturns() throws Exception {
        LlmCancelInterrupt guard = new LlmCancelInterrupt();
        AtomicBoolean interruptedWhileRunning = new AtomicBoolean(false);
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean(true);

        Thread worker = new Thread(() -> {
            // park() returns on interrupt without clearing the flag, which is the state the handler
            // actually observes: the transport re-arms the interrupt when its wait is cut short.
            while (!Thread.currentThread().isInterrupted()) {
                LockSupport.park();
            }
            interruptedWhileRunning.set(true);
            guard.clearIfRaised();
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        }, "llm-cancel-interrupt-test");

        worker.start();
        guard.raise(worker);
        worker.join(5_000L);

        assertFalse(worker.isAlive(), "the cancelled worker was supposed to stop");
        assertTrue(interruptedWhileRunning.get(), "raise() has to reach the thread blocked on the stream");
        assertFalse(interruptedAfterReturn.get(), "the lane thread must go back to the pool clean");
    }

    @Test
    void anInterruptThisRequestDidNotRaiseIsLeftAlone() throws Exception {
        LlmCancelInterrupt guard = new LlmCancelInterrupt();
        AtomicBoolean stillInterrupted = new AtomicBoolean(false);

        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            guard.clearIfRaised();
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        }, "llm-cancel-interrupt-foreign-test");

        worker.start();
        worker.join(5_000L);

        assertFalse(worker.isAlive());
        assertTrue(stillInterrupted.get(), "an interrupt from elsewhere belongs to its own task");
    }
}