package com.rheinmetal.tianshu.function.auxilium.context;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public final class AXRuntimeContextClient {
    private final AXProtocolAdapter adapter;
    private final long timeoutMillis;
    private final ConcurrentMap<String, PendingQuery> pending = new ConcurrentHashMap<>();

    public AXRuntimeContextClient(AXProtocolAdapter adapter) {
        this(adapter, 300L);
    }

    public AXRuntimeContextClient(AXProtocolAdapter adapter, long timeoutMillis) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    public void request(
            TianshuEnvelope parent,
            DialogueDeliveryPayload delivery,
            AXScope scope,
            AXRequest request,
            Completion completion
    ) {
        Objects.requireNonNull(completion, "completion");
        if (parent == null || delivery == null) {
            completion.complete(List.of());
            return;
        }
        PresenceContextQueryPayload payload = new PresenceContextQueryPayload(
                request == null ? "AX.context" : request.requestKey() + ".context",
                delivery.sessionId(),
                delivery.turnId(),
                delivery.playerId(),
                scope == null ? "" : scope.worldId(),
                delivery.contextSnapshot() == null ? "" : delivery.contextSnapshot().dimensionId(),
                request == null ? "" : request.userText(),
                focusIds(delivery),
                System.currentTimeMillis(),
                AXPresenceFactIds.DEFAULT_CONTEXT_FACTS
        );
        if (adapter.presenceContextProviderCount() <= 0) {
            completion.complete(List.of());
            return;
        }
        TianshuEnvelope queryEnvelope = adapter.buildPresenceContextQuery(parent, payload);
        PendingQuery pendingQuery = new PendingQuery(completion, System.currentTimeMillis() + timeoutMillis);
        pending.put(queryEnvelope.envelopeId(), pendingQuery);
        adapter.registerPresenceContextSnapshotResponse(queryEnvelope.envelopeId(), this::handleResponse);
        adapter.submitPresenceContextQuery(queryEnvelope);
        scheduleTimeout(queryEnvelope.envelopeId());
    }

    public void sweepExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(entry -> {
            PendingQuery query = entry.getValue();
            if (query == null || query.deadlineMillis() > now) {
                return false;
            }
            adapter.unregisterPresenceContextResponses(entry.getKey());
            query.completion().complete(List.of());
            return true;
        });
    }

    public void clear() {
        pending.forEach((requestEnvelopeId, query) -> {
            adapter.unregisterPresenceContextResponses(requestEnvelopeId);
            if (query != null) {
                query.completion().complete(List.of());
            }
        });
        pending.clear();
    }

    private void handleResponse(TianshuEnvelope envelope, ProtocolContext context) {
        PendingQuery query = envelope == null ? null : pending.remove(envelope.parentId());
        if (query == null) {
            if (context != null && envelope != null) {
                context.complete(envelope.envelopeId());
            }
            return;
        }
        List<AXRuntimeContextFact> facts = List.of();
        if (envelope.payload() instanceof PresenceContextSnapshotPayload payload && payload.success()) {
            facts = payload.facts().stream()
                    .map(this::toFact)
                    .filter(fact -> !fact.isEmpty())
                    .toList();
        }
        adapter.unregisterPresenceContextResponses(envelope.parentId());
        query.completion().complete(facts);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void scheduleTimeout(String requestEnvelopeId) {
        if (requestEnvelopeId == null || requestEnvelopeId.isBlank()) {
            return;
        }
        CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute(() -> timeout(requestEnvelopeId));
    }

    private void timeout(String requestEnvelopeId) {
        PendingQuery query = pending.remove(requestEnvelopeId);
        if (query == null) {
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        query.completion().complete(List.of());
    }

    private AXRuntimeContextFact toFact(PresenceContextSnapshotPayload.FactPayload fact) {
        return new AXRuntimeContextFact(
                fact.factId(),
                fact.text(),
                fact.priority(),
                fact.source(),
                fact.subject(),
                fact.tags(),
                fact.updatedAtMillis(),
                fact.ttlMillis(),
                fact.nativeValues()
        );
    }

    private List<String> focusIds(DialogueDeliveryPayload delivery) {
        if (delivery == null) {
            return List.of();
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.addAll(delivery.matchedItemIds());
        values.addAll(delivery.matchedEntityTypeIds());
        if (delivery.interactionHints() != null && !delivery.interactionHints().heldItemId().isBlank()) {
            values.add(delivery.interactionHints().heldItemId());
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    public interface Completion {
        void complete(List<AXRuntimeContextFact> facts);
    }

    private record PendingQuery(Completion completion, long deadlineMillis) {
    }
}
