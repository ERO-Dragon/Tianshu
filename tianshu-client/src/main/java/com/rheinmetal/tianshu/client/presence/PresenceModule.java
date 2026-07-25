package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

public final class PresenceModule implements TianshuManagedModule {
    private final PresenceStateStore stateStore;
    private final PresenceActivityTracker activityTracker;
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresenceProtocolAdapter adapter;

    public PresenceModule(
            PresenceProtocolAdapter adapter,
            PresenceStateStore stateStore,
            PresenceActivityTracker activityTracker,
            PresenceDisplayPolicy displayPolicy,
            PresenceContextFactMapper contextFactMapper,
            PresenceContextQueryCoordinator contextQueryCoordinator
    ) {
        this.stateStore = stateStore;
        this.activityTracker = activityTracker;
        this.displayPolicy = displayPolicy;
        this.contextFactMapper = contextFactMapper == null ? new PresenceContextFactMapper() : contextFactMapper;
        this.contextQueryCoordinator = contextQueryCoordinator == null
                ? new PresenceContextQueryCoordinator(stateStore, this.contextFactMapper)
                : contextQueryCoordinator;
        this.contextQueryCoordinator.bindAdapter(adapter);
        this.adapter = adapter;
    }

    @Override
    public String moduleId() {
        return PresenceProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        adapter.registerOwnedTopics(context.protocol());
        adapter.registerQueryContextCapability(this::handleQueryContext);
        adapter.subscribePresenceActivity(this::handlePresenceActivity);
    }

    private void handleQueryContext(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof PresenceContextQueryPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "Presence context query payload is invalid", null);
            return;
        }
        contextQueryCoordinator.handleQuery(envelope, context, payload);
    }

    private void handlePresenceActivity(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof PresenceActivityPayload payload) {
            activityTracker.accept(envelope.header().sourceId(), payload);
        }
        context.complete(envelope.envelopeId());
    }
}

