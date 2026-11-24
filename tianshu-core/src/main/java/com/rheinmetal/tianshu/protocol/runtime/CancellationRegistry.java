package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Who wants to know when an envelope is cancelled.
 *
 * <p>A callback is dropped as soon as it fires: it was registered for one envelope's cancellation, and keeping it
 * afterwards pinned the registering module — usually through a lambda that captured it — for the rest of the session.
 * A callback that throws is isolated, so one consumer's bug cannot stop the others from being told, and cannot escape
 * into the cancelling call itself.</p>
 */
public final class CancellationRegistry {
    private final EnvelopeLifecycleStore lifecycleStore;
    private final Map<String, List<Consumer<TianshuEnvelope>>> callbacks = new ConcurrentHashMap<>();

    public CancellationRegistry(EnvelopeLifecycleStore lifecycleStore) {
        this.lifecycleStore = lifecycleStore;
        lifecycleStore.onTerminal(this::settle);
    }

    private void settle(String envelopeId) {
        List<Consumer<TianshuEnvelope>> registered;
        synchronized (callbacks) {
            registered = callbacks.remove(envelopeId);
        }
        if (registered != null && isCancelled(envelopeId)) {
            lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope ->
                    registered.forEach(callback -> notifyCancellation(callback, envelope)));
        }
    }

    private static void notifyCancellation(Consumer<TianshuEnvelope> callback, TianshuEnvelope envelope) {
        try {
            callback.accept(envelope);
        } catch (RuntimeException ignored) {
            // A subscriber cannot prevent other subscribers from observing the cancellation.
        }
    }

    /** How many envelopes still have a pending cancellation callback; a diagnostic, also used by tests. */
    public int pendingRegistrations() {
        return callbacks.size();
    }

    public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {
        synchronized (callbacks) {
            EnvelopeStatus status = lifecycleStore.statusOf(envelopeId);
            if (!EnvelopeLifecycleStore.isTerminal(status)) {
                if (lifecycleStore.findEnvelope(envelopeId).isEmpty()) {
                    return;
                }
                callbacks.computeIfAbsent(envelopeId, key -> new java.util.ArrayList<>()).add(callback);
                return;
            }
            if (status != EnvelopeStatus.CANCELLED) {
                return;
            }
        }
        lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> notifyCancellation(callback, envelope));
    }

    public boolean isCancelled(String envelopeId) {
        return lifecycleStore.statusOf(envelopeId) == EnvelopeStatus.CANCELLED;
    }

    public void cancelSelf(String envelopeId, String reasonCode, String message) {
        lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> cancelEnvelope(envelope, reasonCode, message));
    }

    public void cancelChildren(String envelopeId, String reasonCode, String message) {
        cancelChildren(envelopeId, reasonCode, message, new HashSet<>());
    }

    public void cancelTrace(String traceId, String reasonCode, String message) {
        for (TianshuEnvelope envelope : lifecycleStore.envelopesByTrace(traceId)) {
            cancelEnvelope(envelope, reasonCode, message);
        }
    }

    /** {@code visited} keeps a malformed parent chain from turning the walk into unbounded recursion. */
    private void cancelChildren(String envelopeId, String reasonCode, String message, Set<String> visited) {
        if (!visited.add(envelopeId)) {
            return;
        }
        lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> cancelEnvelope(envelope, reasonCode, message));
        for (TianshuEnvelope child : lifecycleStore.childrenOf(envelopeId)) {
            cancelChildren(child.envelopeId(), reasonCode, message, visited);
        }
    }

    private void cancelEnvelope(TianshuEnvelope envelope, String reasonCode, String message) {
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.CANCELLED, reasonCode, message);
    }
}
