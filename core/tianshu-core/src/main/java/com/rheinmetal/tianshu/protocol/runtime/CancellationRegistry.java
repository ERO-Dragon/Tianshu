package com.rheinmetal.tianshu.protocol.runtime;

import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class CancellationRegistry {
    private final EnvelopeLifecycleStore lifecycleStore;
    private final Map<String, List<Consumer<TianshuEnvelope>>> callbacks = new ConcurrentHashMap<>();

    public CancellationRegistry(EnvelopeLifecycleStore lifecycleStore) {
        this.lifecycleStore = lifecycleStore;
    }

    public void onCancel(String envelopeId, Consumer<TianshuEnvelope> callback) {
        callbacks.computeIfAbsent(envelopeId, key -> new CopyOnWriteArrayList<>()).add(callback);
    }

    public boolean isCancelled(String envelopeId) {
        return lifecycleStore.statusOf(envelopeId) == EnvelopeStatus.CANCELLED;
    }

    public void cancelSelf(String envelopeId, String reasonCode, String message) {
        lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> cancelEnvelope(envelope, reasonCode, message));
    }

    public void cancelChildren(String envelopeId, String reasonCode, String message) {
        lifecycleStore.findEnvelope(envelopeId).ifPresent(envelope -> cancelEnvelope(envelope, reasonCode, message));
        for (TianshuEnvelope child : lifecycleStore.childrenOf(envelopeId)) {
            cancelChildren(child.envelopeId(), reasonCode, message);
        }
    }

    public void cancelTrace(String traceId, String reasonCode, String message) {
        for (TianshuEnvelope envelope : lifecycleStore.envelopesByTrace(traceId)) {
            cancelEnvelope(envelope, reasonCode, message);
        }
    }

    private void cancelEnvelope(TianshuEnvelope envelope, String reasonCode, String message) {
        EnvelopeStatus status = lifecycleStore.statusOf(envelope.envelopeId());
        if (status == EnvelopeStatus.COMPLETED || status == EnvelopeStatus.CANCELLED || status == EnvelopeStatus.DEAD_LETTERED) {
            return;
        }
        lifecycleStore.transition(envelope.envelopeId(), EnvelopeStatus.CANCELLED, reasonCode, message);
        for (Consumer<TianshuEnvelope> callback : callbacks.getOrDefault(envelope.envelopeId(), List.of())) {
            callback.accept(envelope);
        }
    }
}
