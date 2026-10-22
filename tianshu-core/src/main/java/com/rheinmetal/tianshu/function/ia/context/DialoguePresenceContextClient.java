package com.rheinmetal.tianshu.function.ia.context;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DialoguePresenceContextClient {
    private final IaProtocolAdapter adapter;
    private final DialoguePresenceContextMapper mapper;
    private final long timeoutMillis;
    private final ConcurrentMap<String, PendingQuery> pending = new ConcurrentHashMap<>();

    public DialoguePresenceContextClient(IaProtocolAdapter adapter) {
        this(adapter, new DialoguePresenceContextMapper(), 300L);
    }

    public DialoguePresenceContextClient(IaProtocolAdapter adapter, DialoguePresenceContextMapper mapper, long timeoutMillis) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.mapper = mapper == null ? new DialoguePresenceContextMapper() : mapper;
        this.timeoutMillis = Math.max(0L, timeoutMillis);
    }

    public void request(
            TianshuEnvelope parent,
            String requestId,
            String sessionId,
            String turnId,
            String playerId,
            String userText,
            List<String> requestedFactIds,
            Completion completion,
            Runnable cancelled
    ) {
        Objects.requireNonNull(completion, "completion");
        if (parent == null || adapter.presenceContextProviderCount() <= 0) {
            completion.complete(DialogueContextFrame.empty(playerId));
            return;
        }
        PresenceContextQueryPayload payload = new PresenceContextQueryPayload(
                requestId,
                sessionId,
                turnId,
                playerId,
                "",
                "",
                userText,
                List.of(),
                System.currentTimeMillis(),
                requestedFactIds
        );
        TianshuEnvelope queryEnvelope = adapter.buildPresenceContextQuery(parent, payload);
        PendingQuery pendingQuery = new PendingQuery(playerId, completion, cancelled);
        pending.put(queryEnvelope.envelopeId(), pendingQuery);
        adapter.registerPresenceContextSnapshotResponse(queryEnvelope.envelopeId(), this::handleResponse);
        pendingQuery.timeoutHandle = adapter.schedulePresenceTimeout(
                queryEnvelope.envelopeId(),
                () -> timeout(queryEnvelope.envelopeId()),
                Duration.ofMillis(timeoutMillis)
        );
        adapter.submitPresenceContextQuery(queryEnvelope);
    }

    public void clear() {
        for (String requestEnvelopeId : pending.keySet()) {
            PendingQuery query = pending.remove(requestEnvelopeId);
            adapter.unregisterPresenceContextResponses(requestEnvelopeId);
            if (query != null) {
                query.cancelTimeout("IA_PRESENCE_QUERY_CANCELLED");
                query.cancelled().run();
            }
        }
    }

    private void handleResponse(TianshuEnvelope envelope, ProtocolContext context) {
        String requestEnvelopeId = envelope == null ? "" : envelope.parentId();
        PendingQuery query = pending.remove(requestEnvelopeId);
        if (query == null) {
            if (context != null && envelope != null) {
                context.complete(envelope.envelopeId());
            }
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        query.cancelTimeout("IA_PRESENCE_QUERY_COMPLETED");
        DialogueContextFrame frame = DialogueContextFrame.empty(query.playerId());
        if (envelope.payload() instanceof PresenceContextSnapshotPayload payload && payload.success()) {
            frame = mapper.toFrame(query.playerId(), payload);
        }
        query.completion().complete(frame);
        if (context != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void timeout(String requestEnvelopeId) {
        PendingQuery query = pending.remove(requestEnvelopeId);
        if (query == null) {
            return;
        }
        adapter.unregisterPresenceContextResponses(requestEnvelopeId);
        query.completion().complete(DialogueContextFrame.empty(query.playerId()));
    }

    public interface Completion {
        void complete(DialogueContextFrame frame);
    }

    private static final class PendingQuery {
        private final String playerId;
        private final Completion completion;
        private final Runnable cancelled;
        private volatile ProtocolTaskHandle timeoutHandle;

        private PendingQuery(String playerId, Completion completion, Runnable cancelled) {
            this.playerId = playerId;
            this.completion = completion;
            this.cancelled = cancelled == null ? () -> {
            } : cancelled;
        }

        private void cancelTimeout(String reason) {
            ProtocolTaskHandle handle = timeoutHandle;
            if (handle != null && !handle.isDone()) {
                handle.cancel(reason);
            }
        }

        private String playerId() {
            return playerId;
        }

        private Completion completion() {
            return completion;
        }

        private Runnable cancelled() {
            return cancelled;
        }
    }
}
