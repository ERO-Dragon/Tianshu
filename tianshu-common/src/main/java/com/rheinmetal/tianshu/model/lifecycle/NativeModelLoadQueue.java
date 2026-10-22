package com.rheinmetal.tianshu.model.lifecycle;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide serial gate for native model loads.
 *
 * <p>Every local model in this build — the GGUF embedding lane, the ONNX speech backends — is loaded
 * into <em>native</em> memory that no collector can reclaim, and each load temporarily needs its whole
 * weight set at once. Two loads that overlap therefore add their peaks together, which is what killed
 * the process on a machine whose page file was already exhausted: the embedding load was the largest
 * remaining allocation and it failed on a native {@code malloc}. Serialising the loads is what turns
 * that sum back into a maximum.
 *
 * <p>The queue is a <em>permit dispatcher</em>, not a thread: it never runs a load itself and never
 * blocks. A caller hands over the runnable that submits its load onto its own protocol lane; the queue
 * calls it when the permit is free, and the load's {@code finally} hands the permit back. That keeps
 * every load on the lane that owns its cancellation, interrupt and status semantics, and leaves the
 * queue with exactly one job — deciding who may run now.
 *
 * <p>The embedding model is the <em>primary</em> load. Retrieval is a precondition for dialogue, so a
 * deferred load waits until the primary attempt has settled — success or failure, either way the
 * permit must not be held forever by a model that never loads. {@link #claimPrimary()} is how the
 * owner announces that a primary load is still to come; without it a deferred request would park
 * forever on a build whose LLM module has no embedding model at all.
 *
 * <p>Deliberately not re-entrant and deliberately not fair-by-priority: parked requests are granted in
 * arrival order, because the order the modules ask in is the order the player expects to get their
 * features back.
 */
public final class NativeModelLoadQueue {

    /** Observe park/release so a module can put "queued behind another model" on its status channel. */
    public interface Observer {
        /**
         * The request was parked because another native model holds the permit.
         *
         * @param ahead number of requests already waiting, this one included
         */
        void onParked(String moduleId, String action, int ahead);

        /** The permit was granted and the caller's submit runnable is now running. */
        void onGranted(String moduleId, String action);
    }

    /** Upper bound of parked requests; exceeding it means a module is not releasing its permit. */
    private static final int MAX_PARKED = 8;

    private static final class Request {
        private final String moduleId;
        private final String action;
        private final Runnable submit;
        private final boolean primary;

        private Request(String moduleId, String action, Runnable submit, boolean primary) {
            this.moduleId = moduleId;
            this.action = action;
            this.submit = submit;
            this.primary = primary;
        }
    }

    private final Object lock = new Object();
    private final ArrayDeque<Request> parked = new ArrayDeque<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private Request active;
    private boolean primaryExpected;
    private boolean primarySettled;

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    /**
     * Claims the primary (retrieval) lane.
     *
     * <p>Announces that a primary load is still to come, so deferred requests must wait for its
     * outcome before they may start — and clears a primary permit left behind by a previous attempt
     * whose lane submission was cancelled, which would otherwise park this claim for good.
     *
     * <p>Only a <em>primary</em> permit is abandoned here. A speech load that happens to be running when
     * a new session claims retrieval is left alone: dropping its permit would let the two overlap, which
     * is the one thing this queue exists to prevent.
     */
    public void claimPrimary() {
        synchronized (lock) {
            if (active != null && active.primary) {
                active = null;
            }
            primaryExpected = true;
            primarySettled = false;
        }
    }

    /**
     * The primary attempt has ended — loaded, degraded, unavailable or abandoned. Releases every
     * deferred request that was waiting on it.
     */
    public void settlePrimary() {
        List<Request> granted;
        synchronized (lock) {
            primarySettled = true;
            granted = drainLocked();
        }
        grantAll(granted);
    }

    /** What happened to a request; the caller's handling of a refusal differs from a park. */
    public enum Outcome {
        /** The permit was free: {@code submit} has been called and the load is on its lane. */
        GRANTED,
        /** Another native model holds the permit: {@code submit} will be called when it is handed back. */
        PARKED,
        /** The queue is full of parked requests, which means a permit was never released. */
        REFUSED
    }

    /**
     * Asks for the permit.
     *
     * @param submit runnable that submits the actual load onto the caller's own lane; it must end by
     *               calling {@link #release()}, normally from a {@code finally}
     * @param primary whether this is the retrieval lane that deferred requests wait for
     * @return {@link Outcome#GRANTED}, {@link Outcome#PARKED} or {@link Outcome#REFUSED}
     */
    public Outcome request(String moduleId, String action, Runnable submit, boolean primary) {
        Objects.requireNonNull(submit, "submit");
        Request request = new Request(
                moduleId == null ? "" : moduleId,
                action == null ? "" : action,
                submit,
                primary
        );
        boolean granted = false;
        int ahead = 0;
        synchronized (lock) {
            if (canStartLocked(request)) {
                active = request;
                granted = true;
            } else if (parked.size() >= MAX_PARKED) {
                // Refusing beats growing without bound: a module that never releases the permit is a bug,
                // and the caller still sees the refusal and reports its own queue-full failure.
                return Outcome.REFUSED;
            } else {
                parked.addLast(request);
                ahead = parked.size();
            }
        }
        if (granted) {
            grantAll(List.of(request));
            return Outcome.GRANTED;
        }
        for (Observer observer : observers) {
            observer.onParked(request.moduleId, request.action, ahead);
        }
        return Outcome.PARKED;
    }

    /**
     * Hands the permit back, but only if this module still holds it.
     *
     * <p>Called by the lane task that ran a granted request, whether the load succeeded or not — a
     * failure that never released the permit would park every later model load for the rest of the
     * session.
     *
     * <p>The owner check is what makes release idempotent and safe to call from a teardown path at the
     * same time as the load task itself: a module that no longer holds the permit returns here without
     * touching it, so a second release can never steal the permit from whichever module was granted in
     * the meantime.
     *
     * <p>Releasing the primary request is also what settles it: the retrieval attempt is over, so the
     * deferred speech loads no longer have anything to wait for and one is granted on this same call.
     */
    public void release(String moduleId) {
        String owner = moduleId == null ? "" : moduleId;
        List<Request> granted;
        synchronized (lock) {
            if (active == null || !active.moduleId.equals(owner)) {
                return;
            }
            if (active.primary) {
                primarySettled = true;
            }
            active = null;
            granted = drainLocked();
        }
        grantAll(granted);
    }

    /**
     * Gives up the retrieval claim and hands the gate to whoever was waiting behind it.
     *
     * <p>Used when the LLM module stops: a primary load queued on a lane that will never run must not
     * hold the permit, and a deferred speech load parked behind retrieval may now go ahead. A permit
     * held by a <em>speech</em> load is left alone — that load is in flight and will release its own.
     */
    public void abandonPrimary() {
        List<Request> granted;
        synchronized (lock) {
            if (active != null && active.primary) {
                active = null;
            }
            primaryExpected = false;
            primarySettled = true;
            granted = drainLocked();
        }
        grantAll(granted);
    }

    private boolean canStartLocked(Request request) {
        if (active != null) {
            return false;
        }
        if (request.primary) {
            return true;
        }
        return !primaryExpected || primarySettled;
    }

    private List<Request> drainLocked() {
        if (active != null || parked.isEmpty()) {
            return List.of();
        }
        Request next = parked.peekFirst();
        if (next == null || !canStartLocked(next)) {
            return List.of();
        }
        parked.pollFirst();
        active = next;
        return List.of(next);
    }

    private void grantAll(List<Request> granted) {
        for (Request request : granted) {
            for (Observer observer : observers) {
                observer.onGranted(request.moduleId, request.action);
            }
            try {
                request.submit.run();
            } catch (RuntimeException rejected) {
                // The module's lane refused the submission: the permit must not stay taken, or every
                // later model load in the session would park behind a task that will never run. The
                // owner check makes this a no-op if the submit runnable already handed it back itself
                // before throwing.
                release(request.moduleId);
            }
        }
    }
}
