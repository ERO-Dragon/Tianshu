package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The protocol face of presence: the module that owns {@code PRESENCE.QUERY_CONTEXT}, the
 * {@code PRESENCE.ACTIVITY} subscription and the presence world-event topic.
 *
 * <p>It owns no world state of its own. Everything it touches is created and owned by
 * {@link PresenceClientRuntime}, because a world restart must not destroy the aggregator or the capture path:
 * only their session boundary moves.</p>
 */
public final class PresenceModule implements TianshuManagedModule {
    private final PresenceProtocolAdapter adapter;
    private final PresenceActivityTracker activityTracker;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile boolean lifecycleActive;

    public PresenceModule(
            PresenceProtocolAdapter adapter,
            PresenceActivityTracker activityTracker,
            PresenceContextQueryCoordinator contextQueryCoordinator
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.activityTracker = Objects.requireNonNull(activityTracker, "activityTracker");
        this.contextQueryCoordinator = Objects.requireNonNull(contextQueryCoordinator, "contextQueryCoordinator");
        this.contextQueryCoordinator.bindResponder(adapter::respondContextFacts);
    }

    @Override
    public String moduleId() {
        return PresenceProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        publishLifecycle(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleGeneration.incrementAndGet()));
        adapter.registerOwnedTopics(context.protocol());
        adapter.registerQueryContextCapability(this::handleQueryContext);
        adapter.subscribePresenceActivity(this::handlePresenceActivity);
        lifecycleActive = true;
        publishLifecycle(ModuleStatuses.lifecycleReady(moduleId(), lifecycleGeneration.get()));
    }

    @Override
    public void stop() {
        if (lifecycleActive) {
            lifecycleActive = false;
            publishLifecycle(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleGeneration.get()));
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    private void publishLifecycle(ModuleStatus status) {
        adapter.publishModuleStatus(status);
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
