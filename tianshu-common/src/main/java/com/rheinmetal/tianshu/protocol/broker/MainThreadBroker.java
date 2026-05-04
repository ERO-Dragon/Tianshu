package com.rheinmetal.tianshu.protocol.broker;

import com.rheinmetal.tianshu.protocol.CompletionPolicy;
import com.rheinmetal.tianshu.protocol.EnvelopeStatus;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.registry.HandlerRegistration;
import com.rheinmetal.tianshu.protocol.runtime.MainThreadExecutor;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MainThreadBroker implements ProtocolBroker {
    private final String brokerId;
    private final MainThreadExecutor mainThreadExecutor;
    private final Map<String, TianshuEnvelope> pending = new ConcurrentHashMap<>();
    private final Map<String, TianshuEnvelope> running = new ConcurrentHashMap<>();

    public MainThreadBroker(String brokerId, MainThreadExecutor mainThreadExecutor) {
        this.brokerId = brokerId;
        this.mainThreadExecutor = mainThreadExecutor;
    }

    @Override
    public BrokerSubmitResult submit(TianshuEnvelope envelope, HandlerRegistration registration, ProtocolRuntime runtime) {
        pending.put(envelope.envelopeId(), envelope);
        runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.QUEUED, "QUEUED_MAIN", brokerId);
        mainThreadExecutor.execute(() -> {
            if (pending.remove(envelope.envelopeId()) == null || runtime.cancellation().isCancelled(envelope.envelopeId())) {
                return;
            }
            running.put(envelope.envelopeId(), envelope);
            try {
                if (runtime.cancellation().isCancelled(envelope.envelopeId())) {
                    return;
                }
                runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.RUNNING, "RUNNING_MAIN", brokerId);
                registration.handler().handle(envelope, runtime.context());
                if (registration.capabilityDescriptor().completionPolicy() == CompletionPolicy.AUTO_COMPLETE_ON_RETURN && !runtime.cancellation().isCancelled(envelope.envelopeId())) {
                    runtime.lifecycle().transition(envelope.envelopeId(), EnvelopeStatus.COMPLETED, "COMPLETED", brokerId);
                }
            } catch (Exception exception) {
                runtime.handleFailure(envelope, "MAIN_THREAD_HANDLER_EXCEPTION", exception.getMessage(), exception);
            } finally {
                running.remove(envelope.envelopeId());
            }
        });
        return BrokerSubmitResult.accept();
    }

    @Override
    public BrokerSnapshot snapshot() {
        ArrayList<String> envelopeIds = new ArrayList<>(pending.keySet());
        envelopeIds.addAll(running.keySet());
        return new BrokerSnapshot(brokerId, pending.size(), running.size(), envelopeIds);
    }

    @Override
    public void cancel(String envelopeId, String reasonCode, String message) {
        TianshuEnvelope pendingEnvelope = pending.remove(envelopeId);
        if (pendingEnvelope != null) {
            return;
        }
    }

    @Override
    public String brokerId() {
        return brokerId;
    }
}
