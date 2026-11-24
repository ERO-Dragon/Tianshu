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
    /**
     * Told once when an envelope reaches a terminal status, whoever ended it.
     *
     * <p>Cleanup that hangs off "cancel" alone misses every envelope that simply completed, which is most of them: a
     * callback registered for a request that finishes normally stayed in its map for the life of the process. One
     * notification point here makes release a property of the lifecycle rather than of one code path.</p>
     */
    private final List<java.util.function.Consumer<String>> terminalObservers = new CopyOnWriteArrayList<>();

    /** Subscribes to terminal transitions; called exactly once per envelope, on the transitioning thread. */
    public void onTerminal(java.util.function.Consumer<String> observer) {
        if (observer != null) {
            terminalObservers.add(observer);
        }
    }

    public void accept(TianshuEnvelope envelope) {
        envelopes.put(envelope.envelopeId(), envelope);
        traceIndex.computeIfAbsent(envelope.traceId(), key -> new CopyOnWriteArrayList<>()).add(envelope.envelopeId());
        if (envelope.parentId() != null) {
            parentIndex.computeIfAbsent(envelope.parentId(), key -> new CopyOnWriteArrayList<>()).add(envelope.envelopeId());
        }
        transition(envelope.envelopeId(), EnvelopeStatus.ACCEPTED, "ACCEPTED", "");
    }

    public void transition(String envelopeId, EnvelopeStatus status, String reasonCode, String message) {
        EnvelopeStatus current = statuses.get(envelopeId);
        if (current != null && isTerminal(current) && current != status) {
            // Terminal is final. Letting a late transition overwrite it made the status run backwards — a completed
            // envelope became failed, a rejected one became cancelled — and every observer that compares states saw
            // an impossible sequence.
            return;
        }
        statuses.put(envelopeId, status);
        transitions.computeIfAbsent(envelopeId, key -> new CopyOnWriteArrayList<>()).add(new EnvelopeTransition(envelopeId, status, reasonCode, message, System.currentTimeMillis()));
        if (isTerminal(status) && (current == null || !isTerminal(current))) {
            for (java.util.function.Consumer<String> observer : terminalObservers) {
                try {
                    observer.accept(envelopeId);
                } catch (RuntimeException ignored) {
                    // One observer's bug must not make an envelope untransitionable.
                }
            }
        }
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
        return cleanupTerminalTraces(maxAgeMillis, maxRetainedTraces, 0L);
    }

    /** As above, with a byte budget for retained payloads; {@code maxRetainedBytes <= 0} means no byte limit. */
    public int cleanupTerminalTraces(long maxAgeMillis, int maxRetainedTraces, long maxRetainedBytes) {
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
        // A byte budget on top of the age and count rules: a trace of synthesized audio is hundreds of kilobytes, and
        // "thirty minutes" alone can mean hundreds of megabytes. Oldest terminal traces go first.
        dropPayloadsOverBudget(maxRetainedBytes);
        return removable.size();
    }

    /**
     * Drops the payloads — not the envelopes — of the oldest terminal traces until the retained body bytes fit.
     *
     * <p>Keeping the envelope means a trace stays inspectable and a later cancel still finds its target; only the large
     * body goes. A payload that does not declare a size is assumed small, so modules that never opt in are unaffected.</p>
     */
    private void dropPayloadsOverBudget(long maxRetainedBytes) {
        if (maxRetainedBytes <= 0L) {
            return;
        }
        long total = retainedPayloadBytes();
        if (total <= maxRetainedBytes) {
            return;
        }
        List<TianshuEnvelope> terminal = new ArrayList<>();
        for (TianshuEnvelope envelope : envelopes.values()) {
            if (isTerminal(statusOf(envelope.envelopeId())) && payloadBytes(envelope) > 0) {
                terminal.add(envelope);
            }
        }
        terminal.sort(java.util.Comparator.comparingLong(envelope -> envelope.header().createdAt()));
        for (TianshuEnvelope envelope : terminal) {
            if (total <= maxRetainedBytes) {
                return;
            }
            total -= payloadBytes(envelope);
            envelopes.put(envelope.envelopeId(), new TianshuEnvelope(envelope.header(), null));
        }
    }

    /** Bytes the retained payloads occupy, counted through {@link com.rheinmetal.tianshu.protocol.SizedPayload}. */
    public long retainedPayloadBytes() {
        long total = 0L;
        for (TianshuEnvelope envelope : envelopes.values()) {
            total += payloadBytes(envelope);
        }
        return total;
    }

    private static long payloadBytes(TianshuEnvelope envelope) {
        return envelope.payload() instanceof com.rheinmetal.tianshu.protocol.SizedPayload sized
                ? Math.max(0, sized.retainedBytes())
                : 0L;
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
