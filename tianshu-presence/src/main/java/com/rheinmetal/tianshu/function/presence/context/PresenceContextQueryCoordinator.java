package com.rheinmetal.tianshu.function.presence.context;

import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.function.presence.capture.PresenceContextSnapshotAssembler;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Answers {@code PRESENCE.QUERY_CONTEXT} on the client thread, one bounded batch per tick.
 *
 * <p>The context is captured when a request is answered, not on a timer and not on every input event: nothing
 * is read until somebody asks, and what is read is exactly the union of the groups the drained requests asked
 * for. Requests drained in the same tick therefore share one platform capture, which is the only sharing this
 * module needs.</p>
 *
 * <p>A capture that throws fails the drained requests instead of escaping into the client tick: a broken
 * platform read must degrade the answer, not the game loop.</p>
 */
public final class PresenceContextQueryCoordinator {
    private static final int MAX_PENDING_QUERIES = 64;
    private static final int MAX_QUERIES_PER_TICK = 8;

    private final Queue<PendingContextQuery> pendingQueries = new ArrayBlockingQueue<>(MAX_PENDING_QUERIES);
    private final Object lifecycleMonitor = new Object();
    private final ClientGameContextPort platform;
    private final PresenceContextSnapshotAssembler assembler;
    private final PresenceContextFactMapper factMapper;
    private volatile PresenceContextResponder responder;
    private boolean worldSessionActive;
    private long worldGeneration;

    public PresenceContextQueryCoordinator(ClientGameContextPort platform, PresenceContextFactMapper factMapper) {
        this(platform, new PresenceContextSnapshotAssembler(), factMapper);
    }

    public PresenceContextQueryCoordinator(ClientGameContextPort platform, PresenceContextSnapshotAssembler assembler, PresenceContextFactMapper factMapper) {
        this.platform = Objects.requireNonNull(platform, "platform");
        this.assembler = assembler == null ? new PresenceContextSnapshotAssembler() : assembler;
        this.factMapper = factMapper == null ? new PresenceContextFactMapper() : factMapper;
    }

    public void bindResponder(PresenceContextResponder responder) {
        this.responder = responder;
    }

    public void startWorldSession() {
        synchronized (lifecycleMonitor) {
            worldSessionActive = true;
            worldGeneration++;
        }
    }

    public void stopWorldSession() {
        List<PendingContextQuery> cancelled;
        synchronized (lifecycleMonitor) {
            worldSessionActive = false;
            worldGeneration++;
            cancelled = drainLocked(Integer.MAX_VALUE);
        }
        failAll(cancelled, "PRESENCE_WORLD_STOPPED", "Presence world session stopped");
    }

    public void handleQuery(TianshuEnvelope envelope, ProtocolContext context, PresenceContextQueryPayload payload) {
        EnumSet<PresenceContextGroup> groups = PresenceContextGroup.fromFactIds(payload.requestedFactIds());
        synchronized (lifecycleMonitor) {
            if (!worldSessionActive) {
                context.fail(envelope.envelopeId(), "PRESENCE_NOT_READY", "Presence world session is not ready", null);
                return;
            }
            if (!pendingQueries.offer(new PendingContextQuery(envelope, context, payload, groups, worldGeneration))) {
                context.fail(envelope.envelopeId(), "PRESENCE_BUSY", "Presence context queue is full", null);
            }
        }
    }

    /**
     * Answers at most {@link #MAX_QUERIES_PER_TICK} requests with one capture.
     *
     * <p>Requests that outlive their world session are failed rather than answered from the next world: the
     * caller's own timeout and world-session handling then see a definite end instead of a late answer about a
     * world that no longer exists.</p>
     */
    public void processPending() {
        List<PendingContextQuery> drained = drain(MAX_QUERIES_PER_TICK);
        if (drained.isEmpty()) {
            return;
        }
        long activeGeneration;
        synchronized (lifecycleMonitor) {
            activeGeneration = worldSessionActive ? worldGeneration : -1L;
        }
        if (!isActiveGeneration(activeGeneration)) {
            failAll(drained, "PRESENCE_WORLD_STOPPED", "Presence world session stopped");
            return;
        }
        EnumSet<PresenceContextGroup> requestedGroups = EnumSet.noneOf(PresenceContextGroup.class);
        for (PendingContextQuery pending : drained) {
            requestedGroups.addAll(pending.groups());
        }
        PresenceContextSnapshot captured;
        if (requestedGroups.isEmpty()) {
            // A request that names no fact group gets an answer that carries no fact: it asked for nothing.
            captured = PresenceContextSnapshot.empty();
        } else {
            try {
                captured = assembler.assemble(platform, requestedGroups);
            } catch (RuntimeException failure) {
                failAll(drained, "PRESENCE_CAPTURE_FAILED",
                        "Presence context capture failed: " + failure.getMessage());
                return;
            }
        }
        for (PendingContextQuery pending : drained) {
            if (!isActiveGeneration(activeGeneration) || pending.worldGeneration() != activeGeneration) {
                fail(pending, "PRESENCE_WORLD_STOPPED", "Presence world session stopped");
                continue;
            }
            respond(pending, captured);
        }
    }

    private List<PendingContextQuery> drain(int limit) {
        synchronized (lifecycleMonitor) {
            return drainLocked(limit);
        }
    }

    private List<PendingContextQuery> drainLocked(int limit) {
        List<PendingContextQuery> drained = new ArrayList<>();
        PendingContextQuery pending;
        while (drained.size() < limit && (pending = pendingQueries.poll()) != null) {
            drained.add(pending);
        }
        return drained;
    }

    private boolean isActiveGeneration(long generation) {
        synchronized (lifecycleMonitor) {
            return worldSessionActive && worldGeneration == generation;
        }
    }

    private void respond(PendingContextQuery pending, PresenceContextSnapshot snapshot) {
        PresenceContextResponder currentResponder = responder;
        if (currentResponder == null) {
            fail(pending, "PRESENCE_NOT_READY", "Presence protocol adapter is not ready");
            return;
        }
        currentResponder.respond(
                pending.envelope(),
                pending.payload().requestId(),
                factMapper.factsFrom(snapshot, pending.payload().requestedFactIds()),
                pending.context()
        );
    }

    private void failAll(List<PendingContextQuery> pendingQueries, String reasonCode, String message) {
        for (PendingContextQuery pending : pendingQueries) {
            fail(pending, reasonCode, message);
        }
    }

    private void fail(PendingContextQuery pending, String reasonCode, String message) {
        pending.context().fail(pending.envelope().envelopeId(), reasonCode, message, null);
    }

    private record PendingContextQuery(
            TianshuEnvelope envelope,
            ProtocolContext context,
            PresenceContextQueryPayload payload,
            EnumSet<PresenceContextGroup> groups,
            long worldGeneration
    ) {
        private PendingContextQuery {
            groups = PresenceContextGroup.copyOf(groups);
        }
    }
}
