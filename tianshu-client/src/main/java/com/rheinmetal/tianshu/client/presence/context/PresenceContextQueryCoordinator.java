package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.client.presence.PresenceProtocolAdapter;
import com.rheinmetal.tianshu.client.presence.PresenceStateStore;
import com.rheinmetal.tianshu.client.presence.capture.PresenceEventCollector;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;

public final class PresenceContextQueryCoordinator {
    private static final int MAX_PENDING_QUERIES = 64;
    private static final int MAX_QUERIES_PER_TICK = 8;
    private final Queue<PendingContextQuery> pendingQueries = new ArrayBlockingQueue<>(MAX_PENDING_QUERIES);
    private final Object lifecycleMonitor = new Object();
    private final PresenceStateStore stateStore;
    private final PresenceContextFactMapper factMapper;
    private volatile PresenceProtocolAdapter adapter;
    private volatile boolean worldSessionActive = true;
    private long worldGeneration;

    public PresenceContextQueryCoordinator(PresenceStateStore stateStore, PresenceContextFactMapper factMapper) {
        this.stateStore = stateStore;
        this.factMapper = factMapper == null ? new PresenceContextFactMapper() : factMapper;
    }

    public void bindAdapter(PresenceProtocolAdapter adapter) {
        this.adapter = adapter;
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
            cancelled = drainPending(Integer.MAX_VALUE);
        }
        for (PendingContextQuery pending : cancelled) {
            fail(pending, "PRESENCE_WORLD_STOPPED", "Presence world session stopped");
        }
    }

    public void handleQuery(TianshuEnvelope envelope, ProtocolContext context, PresenceContextQueryPayload payload) {
        long requestGeneration;
        synchronized (lifecycleMonitor) {
            if (!worldSessionActive) {
                context.fail(envelope.envelopeId(), "PRESENCE_NOT_READY", "Presence world session is not ready", null);
                return;
            }
            requestGeneration = worldGeneration;
        }
        EnumSet<PresenceContextGroup> groups = PresenceContextGroup.fromFactIds(payload.requestedFactIds());
        if (groups.isEmpty()) {
            boolean active;
            synchronized (lifecycleMonitor) {
                active = worldSessionActive && requestGeneration == worldGeneration;
            }
            if (!active) {
                context.fail(envelope.envelopeId(), "PRESENCE_NOT_READY", "Presence world session is not ready", null);
                return;
            }
            respond(envelope, context, payload, stateStore.contextSnapshot());
            return;
        }
        synchronized (lifecycleMonitor) {
            if (!worldSessionActive || requestGeneration != worldGeneration) {
                context.fail(envelope.envelopeId(), "PRESENCE_NOT_READY", "Presence world session is not ready", null);
                return;
            }
            if (!pendingQueries.offer(new PendingContextQuery(envelope, context, payload, groups, requestGeneration))) {
                context.fail(envelope.envelopeId(), "PRESENCE_BUSY", "Presence context queue is full", null);
            }
        }
    }

    public void processPending(PresenceEventCollector collector) {
        List<PendingContextQuery> drained = drainPending(MAX_QUERIES_PER_TICK);
        if (drained.isEmpty()) {
            return;
        }
        long activeGeneration;
        synchronized (lifecycleMonitor) {
            activeGeneration = worldSessionActive ? worldGeneration : -1L;
        }
        EnumSet<PresenceContextGroup> refreshGroups = EnumSet.noneOf(PresenceContextGroup.class);
        for (PendingContextQuery pending : drained) {
            refreshGroups.addAll(stateStore.groupsNeedingRefresh(pending.groups()));
        }
        if (!refreshGroups.isEmpty() && isActiveGeneration(activeGeneration)) {
            PresenceContextSnapshot captured = collector.captureGroups(refreshGroups);
            stateStore.updateGroups(captured, refreshGroups);
        }
        for (PendingContextQuery pending : drained) {
            boolean active;
            synchronized (lifecycleMonitor) {
                active = activeGeneration >= 0L
                        && pending.worldGeneration() == worldGeneration
                        && worldSessionActive;
            }
            if (!active) {
                fail(pending, "PRESENCE_WORLD_STOPPED", "Presence world session stopped");
                continue;
            }
            respond(pending.envelope(), pending.context(), pending.payload(), stateStore.contextSnapshot());
        }
    }

    private List<PendingContextQuery> drainPending(int limit) {
        synchronized (lifecycleMonitor) {
            List<PendingContextQuery> drained = new ArrayList<>();
            PendingContextQuery pending;
            while (drained.size() < limit && (pending = pendingQueries.poll()) != null) {
                drained.add(pending);
            }
            return drained;
        }
    }

    private boolean isActiveGeneration(long generation) {
        synchronized (lifecycleMonitor) {
            return worldSessionActive && worldGeneration == generation;
        }
    }

    private void respond(
            TianshuEnvelope envelope,
            ProtocolContext context,
            PresenceContextQueryPayload query,
            PresenceContextSnapshot snapshot
    ) {
        PresenceProtocolAdapter currentAdapter = adapter;
        if (currentAdapter == null) {
            context.fail(envelope.envelopeId(), "PRESENCE_NOT_READY", "Presence protocol adapter is not ready", null);
            return;
        }
        currentAdapter.respondContext(envelope, PresenceContextSnapshotPayload.success(
                query.requestId(),
                factMapper.factsFrom(snapshot, query.requestedFactIds())
        ));
        context.complete(envelope.envelopeId());
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
