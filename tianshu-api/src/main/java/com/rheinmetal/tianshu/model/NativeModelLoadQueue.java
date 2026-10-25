package com.rheinmetal.tianshu.model;

import java.util.ArrayList;
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
 * <p>Order is part of the contract, not something the callers fake with delays: {@link Tier} is the
 * order, and it lives here. Retrieval goes first because dialogue depends on it, speech output second
 * because it is the next thing the player notices, speech input last because the module refuses to
 * record until it is up anyway. A lower tier always wins, whether it asked earlier or later.
 *
 * <p>A tier can announce that a load of its own is still to come ({@link #expect(Tier)}); a better tier
 * never waits for a worse one, but a worse tier waits for every better tier that is expected and not
 * yet settled. The permit must not be held forever by a model that never loads, so a tier that decides
 * not to load settles or abandons its claim — {@link #claimPrimary()} is how the retrieval lane
 * announces itself, and the speech-output tier is expected from the start because a voice-input load
 * must not overtake it even when the two ask in the opposite order.
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

    /**
     * Load order. The rank is the whole priority policy — nothing outside this type may reorder loads.
     */
    public enum Tier {
        /** Retrieval: dialogue depends on it, so it runs before anything else. */
        PRIMARY(0),
        /** Speech output: the next capability the player notices, and the one synthesis needs. */
        DEFERRED(1),
        /** Speech input: last, because the module refuses to record until its model is up. */
        LAST(2);

        private final int rank;

        Tier(int rank) {
            this.rank = rank;
        }

        int rank() {
            return rank;
        }

        static Tier byRank(int rank) {
            for (Tier tier : values()) {
                if (tier.rank == rank) {
                    return tier;
                }
            }
            throw new IllegalArgumentException("No load tier has rank " + rank);
        }
    }

    /** Upper bound of parked requests; exceeding it means a module is not releasing its permit. */
    private static final int MAX_PARKED = 8;
    private static final int TIER_COUNT = Tier.values().length;

    private static final class Request {
        private final String moduleId;
        private final String action;
        private final Runnable submit;
        private final Tier tier;

        private Request(String moduleId, String action, Runnable submit, Tier tier) {
            this.moduleId = moduleId;
            this.action = action;
            this.submit = submit;
            this.tier = tier;
        }
    }

    private final Object lock = new Object();
    /** Kept rank-ordered, arrival-ordered inside a rank; see {@link #parkLocked(Request)}. */
    private final List<Request> parked = new ArrayList<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    /** Per rank: whether a load of that tier is still to come, and whether it has already ended. */
    private final boolean[] tierExpected = new boolean[TIER_COUNT];
    private final boolean[] tierSettled = new boolean[TIER_COUNT];
    private Request active;

    public NativeModelLoadQueue() {
        // Retrieval announces itself with claimPrimary(); the speech-output tier is expected from the
        // start, so a voice-input load cannot slip in ahead of it when it happens to ask first.
        tierSettled[Tier.LAST.rank()] = true;
        tierExpected[Tier.DEFERRED.rank()] = true;
    }

    public void addObserver(Observer observer) {
        if (observer != null && !observers.contains(observer)) {
            observers.add(observer);
        }
    }

    public void removeObserver(Observer observer) {
        observers.remove(observer);
    }

    /** Announces that a load of {@code tier} is still to come; worse tiers wait for it to settle. */
    public void expect(Tier tier) {
        Objects.requireNonNull(tier, "tier");
        synchronized (lock) {
            expectLocked(tier);
        }
    }

    /**
     * Claims the primary (retrieval) lane.
     *
     * <p>Announces that a primary load is still to come, so worse tiers must wait for its outcome before
     * they may start — and clears a primary permit left behind by a previous attempt whose lane submission
     * was cancelled, which would otherwise park this claim for good.
     *
     * <p>Only a <em>primary</em> permit is abandoned here. A speech load that happens to be running when a
     * new session claims retrieval is left alone: dropping its permit would let the two overlap, which is
     * the one thing this queue exists to prevent.
     */
    public void claimPrimary() {
        synchronized (lock) {
            if (active != null && active.tier == Tier.PRIMARY) {
                active = null;
            }
            expectLocked(Tier.PRIMARY);
        }
    }

    /** The primary attempt has ended — loaded, degraded, unavailable or abandoned. */
    public void settlePrimary() {
        settle(Tier.PRIMARY);
    }

    /**
     * The attempt of {@code tier} has ended — loaded, degraded, unavailable or abandoned. Whatever was
     * waiting for it is granted on this same call.
     */
    public void settle(Tier tier) {
        Objects.requireNonNull(tier, "tier");
        List<Request> granted;
        synchronized (lock) {
            tierSettled[tier.rank()] = true;
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
     * Asks for the permit at the given tier.
     *
     * @param submit runnable that submits the actual load onto the caller's own lane; it must end by
     *               calling {@link #release(String)}, normally from a {@code finally}
     * @param tier   where this load sits in the order; see {@link Tier}
     * @return {@link Outcome#GRANTED}, {@link Outcome#PARKED} or {@link Outcome#REFUSED}
     */
    public Outcome request(String moduleId, String action, Runnable submit, Tier tier) {
        Objects.requireNonNull(submit, "submit");
        Objects.requireNonNull(tier, "tier");
        Request request = new Request(
                moduleId == null ? "" : moduleId,
                action == null ? "" : action,
                submit,
                tier
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
                parkLocked(request);
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
     * <p>Called by the lane task that ran a granted request, whether the load succeeded or not — a failure
     * that never released the permit would park every later model load for the rest of the session.
     *
     * <p>The owner check is what makes release idempotent and safe to call from a teardown path at the same
     * time as the load task itself: a module that no longer holds the permit returns here without touching
     * it, so a second release can never steal the permit from whichever module was granted in the meantime.
     *
     * <p>Releasing also settles the holder's tier: the attempt is over, so every worse tier that was
     * waiting on it is granted on this same call.
     */
    public void release(String moduleId) {
        String owner = moduleId == null ? "" : moduleId;
        List<Request> granted;
        synchronized (lock) {
            if (active == null || !active.moduleId.equals(owner)) {
                return;
            }
            tierSettled[active.tier.rank()] = true;
            active = null;
            granted = drainLocked();
        }
        grantAll(granted);
    }

    /**
     * Gives up the claim of {@code tier} and hands the gate to whoever was waiting behind it.
     *
     * <p>Used when a module stops or decides not to load: a load queued on a lane that will never run must
     * not hold a claim, and the worse tiers parked behind it may now go ahead. A permit held by a load of
     * that tier is cleared, because that load will never run either; a permit held by another tier is left
     * alone and releases its own.
     */
    public void abandon(Tier tier) {
        Objects.requireNonNull(tier, "tier");
        List<Request> granted;
        synchronized (lock) {
            if (active != null && active.tier == tier) {
                active = null;
            }
            tierExpected[tier.rank()] = false;
            tierSettled[tier.rank()] = true;
            granted = drainLocked();
        }
        grantAll(granted);
    }

    /** Gives up the retrieval claim; see {@link #abandon(Tier)}. */
    public void abandonPrimary() {
        abandon(Tier.PRIMARY);
    }

    private void expectLocked(Tier tier) {
        tierExpected[tier.rank()] = true;
        tierSettled[tier.rank()] = false;
    }

    /**
     * Keeps the waiting list rank-ordered and arrival-ordered inside one rank.
     *
     * <p>Inserting after every request of the same or a better rank is what makes the order a promise:
     * an equal rank never overtakes an equal rank, and a worse rank never overtakes a better one no matter
     * which of them asked first.
     */
    private void parkLocked(Request request) {
        int index = 0;
        while (index < parked.size() && parked.get(index).tier.rank() <= request.tier.rank()) {
            index++;
        }
        parked.add(index, request);
    }

    private boolean canStartLocked(Request request) {
        if (active != null) {
            return false;
        }
        for (int rank = 0; rank < request.tier.rank(); rank++) {
            Tier better = Tier.byRank(rank);
            if (tierExpected[rank] && !tierSettled[rank]) {
                // A better tier is still to come; starting now would make it wait behind this one.
                return false;
            }
            if (hasParkedLocked(better)) {
                // A better tier is already waiting; it must not be overtaken by this request.
                return false;
            }
        }
        return true;
    }

    private boolean hasParkedLocked(Tier tier) {
        for (Request request : parked) {
            if (request.tier == tier) {
                return true;
            }
        }
        return false;
    }

    private List<Request> drainLocked() {
        if (active != null || parked.isEmpty()) {
            return List.of();
        }
        Request next = parked.get(0);
        if (next == null || !canStartLocked(next)) {
            return List.of();
        }
        parked.remove(0);
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
