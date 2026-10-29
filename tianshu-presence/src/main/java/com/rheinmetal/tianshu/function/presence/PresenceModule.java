package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudHost;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import com.rheinmetal.tianshu.function.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerStore;
import com.rheinmetal.tianshu.function.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceActivityPayload;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextQueryPayload;
import com.rheinmetal.tianshu.protocol.payload.PresencePartnerPayload;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.status.ModuleStatus;
import com.rheinmetal.tianshu.protocol.status.ModuleStatuses;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The protocol face of presence: the module that owns {@code PRESENCE.QUERY_CONTEXT}, the
 * {@code PRESENCE.ACTIVITY} and {@code PRESENCE.PARTNER} subscriptions and the presence world-event topic.
 *
 * <p>It owns no world state of its own. Everything it touches is created and owned by
 * {@link PresenceClientRuntime}; the module's lifecycle is the session's: registering opens it and adds the HUD
 * layer, stopping closes it and takes the layer back.</p>
 */
public final class PresenceModule implements TianshuManagedModule {
    private final PresenceProtocolAdapter adapter;
    private final PresenceActivityTracker activityTracker;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresencePartnerStore partnerStore;
    private final Consumer<WorldEventPayload> externalEventSink;
    private final PresenceClientRuntime session;
    private final HudHost hud;
    private final HudLayer layer;
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile boolean lifecycleActive;

    public PresenceModule(
            PresenceProtocolAdapter adapter,
            PresenceActivityTracker activityTracker,
            PresenceContextQueryCoordinator contextQueryCoordinator,
            PresencePartnerStore partnerStore,
            Consumer<WorldEventPayload> externalEventSink,
            PresenceClientRuntime session,
            HudHost hud,
            HudLayer layer
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.activityTracker = Objects.requireNonNull(activityTracker, "activityTracker");
        this.contextQueryCoordinator = Objects.requireNonNull(contextQueryCoordinator, "contextQueryCoordinator");
        this.partnerStore = Objects.requireNonNull(partnerStore, "partnerStore");
        this.externalEventSink = externalEventSink == null ? payload -> { } : externalEventSink;
        this.session = Objects.requireNonNull(session, "session");
        this.hud = Objects.requireNonNull(hud, "hud");
        this.layer = Objects.requireNonNull(layer, "layer");
        this.contextQueryCoordinator.bindResponder(adapter::respondContextFacts);
    }

    @Override
    public String moduleId() {
        return PresenceProtocolAdapter.MODULE_ID;
    }

    @Override
    public void register(ModuleRegistrationContext context) {
        publishLifecycle(ModuleStatuses.lifecycleStarting(moduleId(), lifecycleGeneration.incrementAndGet()));
        session.startWorldSession();
        hud.addLayer(layer);
        adapter.registerOwnedTopics(context.protocol());
        adapter.registerQueryContextCapability(this::handleQueryContext);
        adapter.subscribePresenceActivity(this::handlePresenceActivity);
        adapter.subscribePartner(this::handlePartner);
        adapter.subscribeWorldEventInput(this::handleWorldEventInput);
        lifecycleActive = true;
        publishLifecycle(ModuleStatuses.lifecycleReady(moduleId(), lifecycleGeneration.get()));
    }

    @Override
    public void stop() {
        if (lifecycleActive) {
            lifecycleActive = false;
            hud.removeLayer(layer.id());
            session.stopWorldSession();
            publishLifecycle(ModuleStatuses.lifecycleStopped(moduleId(), lifecycleGeneration.get()));
        }
    }

    @Override
    public void destroy() {
        stop();
        partnerStore.clear();
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

    /** One raw platform event: handed to the aggregation window, never rendered or answered here. */
    private void handleWorldEventInput(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof WorldEventPayload payload) {
            externalEventSink.accept(payload);
        }
        context.complete(envelope.envelopeId());
    }

    private void handlePartner(TianshuEnvelope envelope, ProtocolContext context) {
        if (envelope.payload() instanceof PresencePartnerPayload payload) {
            partnerStore.accept(payload);
        }
        context.complete(envelope.envelopeId());
    }
}