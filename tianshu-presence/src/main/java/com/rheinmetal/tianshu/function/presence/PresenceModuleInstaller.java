package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudHost;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerStore;
import com.rheinmetal.tianshu.function.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Registers the one presence module; the runtime owns every dependency it needs.
 *
 * <p>The installer also carries the HUD layer's registration target, because the platform's HUD host is a session
 * service: the layer is added when the module registers and removed when it stops, so the two lifetimes cannot drift.</p>
 */
public final class PresenceModuleInstaller implements TianshuModuleInstaller {
    private final PresenceProtocolAdapter adapter;
    private final PresenceActivityTracker activityTracker;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresencePartnerStore partnerStore;
    private final Consumer<WorldEventPayload> externalEventSink;
    private final PresenceClientRuntime session;
    private final HudHost hud;
    private final HudLayer layer;

    public PresenceModuleInstaller(
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
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(
                adapter, activityTracker, contextQueryCoordinator, partnerStore, externalEventSink,
                session, hud, layer));
    }
}
