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
    private final Queue<PendingContextQuery> pendingQueries = new ArrayBlockingQueue<>(MAX_PENDING_QUERIES);
    private final PresenceStateStore stateStore;
    private final PresenceContextFactMapper factMapper;
    private volatile PresenceProtocolAdapter adapter;

    public PresenceContextQueryCoordinator(PresenceStateStore stateStore, PresenceContextFactMapper factMapper) {
        this.stateStore = stateStore;
        this.factMapper = factMapper == null ? new PresenceContextFactMapper() : factMapper;
    }

    public void bindAdapter(PresenceProtocolAdapter adapter) {
        this.adapter = adapter;
    }

    public void handleQuery(TianshuEnvelope envelope, ProtocolContext context, PresenceContextQueryPayload payload) {
        EnumSet<PresenceContextGroup> groups = PresenceContextGroup.fromFactIds(payload.requestedFactIds());
        if (groups.isEmpty()) {
            respond(envelope, context, payload, stateStore.contextSnapshot());
            return;
        }
        EnumSet<PresenceContextGroup> refreshGroups = stateStore.groupsNeedingRefresh(groups);
        if (refreshGroups.isEmpty()) {
            respond(envelope, context, payload, stateStore.contextSnapshot());
            return;
        }
        if (!pendingQueries.offer(new PendingContextQuery(envelope, context, payload, groups))) {
            context.fail(envelope.envelopeId(), "PRESENCE_BUSY", "Presence context queue is full", null);
        }
    }

    public void processPending(PresenceEventCollector collector) {
        List<PendingContextQuery> drained = drainPending();
        if (drained.isEmpty()) {
            return;
        }
        EnumSet<PresenceContextGroup> refreshGroups = EnumSet.noneOf(PresenceContextGroup.class);
        for (PendingContextQuery pending : drained) {
            refreshGroups.addAll(stateStore.groupsNeedingRefresh(pending.groups()));
        }
        if (!refreshGroups.isEmpty()) {
            PresenceContextSnapshot captured = collector.captureGroups(refreshGroups);
            stateStore.updateGroups(captured, refreshGroups);
        }
        for (PendingContextQuery pending : drained) {
            respond(pending.envelope(), pending.context(), pending.payload(), stateStore.contextSnapshot());
        }
    }

    private List<PendingContextQuery> drainPending() {
        List<PendingContextQuery> drained = new ArrayList<>();
        PendingContextQuery pending;
        while ((pending = pendingQueries.poll()) != null) {
            drained.add(pending);
        }
        return drained;
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

    private record PendingContextQuery(
            TianshuEnvelope envelope,
            ProtocolContext context,
            PresenceContextQueryPayload payload,
            EnumSet<PresenceContextGroup> groups
    ) {
        private PendingContextQuery {
            groups = PresenceContextGroup.copyOf(groups);
        }
    }
}
