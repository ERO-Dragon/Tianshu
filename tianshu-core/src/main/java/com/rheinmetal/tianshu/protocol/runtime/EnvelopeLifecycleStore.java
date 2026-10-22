package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class EnvelopeLifecycleStore {
    private final Map<String, TianshuEnvelope> envelopes = new ConcurrentHashMap<>();
    private final Map<String, EnvelopeStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, List<EnvelopeTransition>> transitions = new ConcurrentHashMap<>();
    private final Map<String, List<String>> traceIndex = new ConcurrentHashMap<>();
    private final Map<String, List<String>> parentIndex = new ConcurrentHashMap<>();

    public void accept(TianshuEnvelope envelope) {
        envelopes.put(envelope.envelopeId(), envelope);
        traceIndex.computeIfAbsent(envelope.traceId(), key -> new CopyOnWriteArrayList<>()).add(envelope.envelopeId());
        if (envelope.parentId() != null) {
            parentIndex.computeIfAbsent(envelope.parentId(), key -> new CopyOnWriteArrayList<>()).add(envelope.envelopeId());
        }
        transition(envelope.envelopeId(), EnvelopeStatus.ACCEPTED, "ACCEPTED", "");
    }

    public void transition(String envelopeId, EnvelopeStatus status, String reasonCode, String message) {
        statuses.put(envelopeId, status);
        transitions.computeIfAbsent(envelopeId, key -> new CopyOnWriteArrayList<>()).add(new EnvelopeTransition(envelopeId, status, reasonCode, message, System.currentTimeMillis()));
    }

    public Optional<TianshuEnvelope> findEnvelope(String envelopeId) {
        return Optional.ofNullable(envelopes.get(envelopeId));
    }

    public EnvelopeStatus statusOf(String envelopeId) {
        return statuses.getOrDefault(envelopeId, EnvelopeStatus.CREATED);
    }

    public List<TianshuEnvelope> envelopesByTrace(String traceId) {
        List<String> ids = traceIndex.getOrDefault(traceId, List.of());
        List<TianshuEnvelope> result = new ArrayList<>();
        for (String id : ids) {
            TianshuEnvelope envelope = envelopes.get(id);
            if (envelope != null) result.add(envelope);
        }
        return result;
    }

    public List<TianshuEnvelope> childrenOf(String envelopeId) {
        List<String> ids = parentIndex.getOrDefault(envelopeId, List.of());
        List<TianshuEnvelope> result = new ArrayList<>();
        for (String id : ids) {
            TianshuEnvelope envelope = envelopes.get(id);
            if (envelope != null) result.add(envelope);
        }
        return result;
    }

    public List<EnvelopeTransition> transitionsOf(String envelopeId) {
        return List.copyOf(transitions.getOrDefault(envelopeId, List.of()));
    }

    public List<EnvelopeTransition> allTransitions() {
        List<EnvelopeTransition> result = new ArrayList<>();
        for (List<EnvelopeTransition> item : transitions.values()) {
            result.addAll(item);
        }
        return result;
    }

    public List<TianshuEnvelope> allEnvelopes() {
        return new ArrayList<>(envelopes.values());
    }

    public void cleanupTerminalTraces(long maxAgeMillis, int maxRetainedTraces) {
        long now = System.currentTimeMillis();
        List<String> removable = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : traceIndex.entrySet()) {
            boolean terminal = true;
            boolean old = false;
            for (String id : entry.getValue()) {
                TianshuEnvelope envelope = envelopes.get(id);
                EnvelopeStatus status = statusOf(id);
                terminal = terminal && isTerminal(status);
                old = old || envelope != null && now - envelope.header().createdAt() > maxAgeMillis;
            }
            if ((terminal && old) || traceIndex.size() > maxRetainedTraces) {
                removable.add(entry.getKey());
            }
        }
        for (String traceId : removable) {
            List<String> ids = traceIndex.remove(traceId);
            if (ids == null) continue;
            for (String id : ids) {
                envelopes.remove(id);
                statuses.remove(id);
                transitions.remove(id);
                parentIndex.remove(id);
            }
        }
    }

    private boolean isTerminal(EnvelopeStatus status) {
        return status == EnvelopeStatus.COMPLETED || status == EnvelopeStatus.CANCELLED || status == EnvelopeStatus.EXPIRED || status == EnvelopeStatus.FAILED || status == EnvelopeStatus.REJECTED || status == EnvelopeStatus.DEAD_LETTERED;
    }
}
