package com.rheinmetal.tianshu.weaving;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The interrupt one request raises to stop its own provider stream.
 *
 * <p>An inbound {@code CANCEL} has to reach the thread that is blocked on the HTTP exchange, and the
 * only mechanism the transport offers is {@link Thread#interrupt()}. That thread belongs to the host's
 * lane and is reused for the next envelope, so the flag it raises may not outlive the request: a
 * leftover interrupt would abort whichever request the lane hands the same thread next.</p>
 *
 * <p>Ownership is therefore tracked here instead of being assumed. Only an interrupt this instance
 * raised is cleared, and only while it is still pending; an interrupt that arrived from somewhere else
 * belongs to its own task and is left alone.</p>
 */
final class LlmCancelInterrupt {

    private final AtomicBoolean raised = new AtomicBoolean(false);

    /** Interrupts the worker once; repeated calls are ignored. */
    void raise(Thread worker) {
        if (worker != null && raised.compareAndSet(false, true)) {
            worker.interrupt();
        }
    }

    /**
     * Drops the pending interrupt this request raised, if any.
     *
     * <p>Called when the handler returns. The caller runs on the worker itself, which is what makes
     * {@link Thread#interrupted()} the right call: it clears the current thread's flag and reports it,
     * so this can neither clear a flag it exported elsewhere nor report success when nothing was set.</p>
     */
    void clearIfRaised() {
        if (raised.getAndSet(false)) {
            Thread.interrupted();
        }
    }
}