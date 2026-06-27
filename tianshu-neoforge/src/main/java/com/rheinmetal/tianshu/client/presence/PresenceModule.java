package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.AsrSpeechActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.payload.ModuleStatusPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackStatusPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

public final class PresenceModule implements TianshuManagedModule {
    private final PresenceStateStore stateStore;
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;
    private final PresenceProtocolAdapter adapter;
    private final PresenceModuleStatusMapper moduleStatusMapper;

    public PresenceModule(
            PresenceProtocolAdapter adapter,
            PresenceStateStore stateStore,
            PresenceDisplayPolicy displayPolicy,
            PresenceContextFactMapper contextFactMapper
    ) {
        this.stateStore = stateStore;
        this.displayPolicy = displayPolicy;
        this.contextFactMapper = contextFactMapper == null ? new PresenceContextFactMapper() : contextFactMapper;
        this.adapter = adapter;
        this.moduleStatusMapper = new PresenceModuleStatusMapper();
    }

    @Override
    public String moduleId() {
        return PresenceProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerOwnedTopics(context.protocol());
        adapter.registerQueryContextCapability(this::handleQueryContext);
        adapter.subscribeAsrSpeechActivity(this::handleAsrSpeechActivity);
        adapter.subscribeLlmStatus(this::handleLlmStatus);
        adapter.subscribeTtsPlayback(this::handleTtsPlayback);
        adapter.subscribeModuleStatus(this::handleModuleStatus);
    }

    private void handleQueryContext(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof PresenceContextQueryPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Presence context query payload is invalid", null);
            return;
        }
        PresenceContextSnapshot snapshot = stateStore.freshestDetailedContextSnapshot(PresenceRefreshPolicy.DETAILED_SNAPSHOT_STALE_MILLIS);
        adapter.respondContext(envelope, PresenceContextSnapshotPayload.success(
                payload.requestId(),
                contextFactMapper.factsFrom(snapshot, stateStore.recentEvents(8), payload.requestedFactIds())
        ));
        context.complete(envelope.envelopeId());
    }

    private void handleAsrSpeechActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof AsrSpeechActivityPayload payload) {
            stateStore.updateStatus(displayPolicy.fromAsr(payload));
        }
        context.complete(envelope.envelopeId());
    }

    private void handleLlmStatus(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof LlmStatusPayload payload) {
            stateStore.updateStatus(displayPolicy.fromLlm(payload));
        }
        context.complete(envelope.envelopeId());
    }

    private void handleTtsPlayback(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof TtsPlaybackStatusPayload payload) {
            stateStore.updateStatus(displayPolicy.fromTts(payload));
        }
        context.complete(envelope.envelopeId());
    }

    private void handleModuleStatus(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof ModuleStatusPayload payload) {
            PresenceStatusSnapshot status = moduleStatusMapper.fromStatus(payload.status());
            if (status != null) {
                stateStore.updateStatus(status);
            }
        }
        context.complete(envelope.envelopeId());
    }
}

