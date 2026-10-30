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

    /**
     * Drops history that can no longer answer a question.
     *
     * <p>A trace is eligible only when every envelope in it reached a terminal status: cancellation,
     * response routing and trace queries all read this store, so dropping a trace with an envelope still
     * in flight would turn a later cancel into "target not found". Among eligible traces, the ones whose
     * newest envelope is older than {@code maxAgeMillis} go first; if that still leaves more than
     * {@code maxRetainedTraces}, the oldest eligible traces go next.</p>
     *
     * @return how many traces were dropped
     */
    public int cleanupTerminalTraces(long maxAgeMillis, int maxRetainedTraces) {
        long now = System.currentTimeMillis();
        int cap = Math.max(1, maxRetainedTraces);
        List<String> removable = new ArrayList<>();
        List<String> eligible = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : traceIndex.entrySet()) {
            long newest = Long.MIN_VALUE;
            boolean terminal = true;
            for (String id : entry.getValue()) {
                TianshuEnvelope envelope = envelopes.get(id);
                if (envelope == null) {
                    continue;
                }
                newest = Math.max(newest, envelope.header().createdAt());
                if (!isTerminal(statusOf(id))) {
                    terminal = false;
                }
            }
            if (!terminal) {
                continue;
            }
            eligible.add(entry.getKey());
            if (newest != Long.MIN_VALUE && now - newest > maxAgeMillis) {
                removable.add(entry.getKey());
            }
        }
        int remaining = traceIndex.size() - removable.size();
        if (remaining > cap) {
            List<String> oldestFirst = new ArrayList<>(eligible);
            oldestFirst.removeAll(removable);
            oldestFirst.sort(java.util.Comparator.comparingLong(this::newestEnvelopeAt));
            int excess = remaining - cap;
            for (int index = 0; index < Math.min(excess, oldestFirst.size()); index++) {
                removable.add(oldestFirst.get(index));
            }
        }
        for (String traceId : removable) {
            dropTrace(traceId);
        }
        return removable.size();
    }

    private long newestEnvelopeAt(String traceId) {
        long newest = Long.MIN_VALUE;
        for (String id : traceIndex.getOrDefault(traceId, List.of())) {
            TianshuEnvelope envelope = envelopes.get(id);
            if (envelope != null) {
                newest = Math.max(newest, envelope.header().createdAt());
            }
        }
        return newest;
    }

    private void dropTrace(String traceId) {
        List<String> ids = traceIndex.remove(traceId);
        if (ids == null) {
            return;
        }
        List<String> kept = new ArrayList<>();
        for (String id : ids) {
            // Eligibility was decided before this loop; a producer may have joined the same trace since.
            // Anything still in flight stays, and the trace index keeps pointing at it.
            if (!isTerminal(statusOf(id))) {
                kept.add(id);
                continue;
            }
            TianshuEnvelope envelope = envelopes.remove(id);
            statuses.remove(id);
            transitions.remove(id);
            String parentId = envelope == null ? null : envelope.parentId();
            if (parentId == null) {
                parentIndex.remove(id);
                continue;
            }
            List<String> children = parentIndex.get(parentId);
            if (children != null) {
                children.remove(id);
                if (children.isEmpty()) {
                    parentIndex.remove(parentId);
                }
            }
        }
        if (!kept.isEmpty()) {
            traceIndex.putIfAbsent(traceId, new CopyOnWriteArrayList<>(kept));
        }
    }

    private boolean isTerminal(EnvelopeStatus status) {
        return status == EnvelopeStatus.COMPLETED || status == EnvelopeStatus.CANCELLED || status == EnvelopeStatus.EXPIRED || status == EnvelopeStatus.FAILED || status == EnvelopeStatus.REJECTED || status == EnvelopeStatus.DEAD_LETTERED;
    }
}
