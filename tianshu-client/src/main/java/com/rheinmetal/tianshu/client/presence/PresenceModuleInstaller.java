package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerStore;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

import java.util.Objects;

/** Registers the one presence module; the runtime owns every dependency it needs. */
public final class PresenceModuleInstaller implements TianshuModuleInstaller {
    private final PresenceProtocolAdapter adapter;
    private final PresenceActivityTracker activityTracker;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;
    private final PresencePartnerStore partnerStore;

    public PresenceModuleInstaller(
            PresenceProtocolAdapter adapter,
            PresenceActivityTracker activityTracker,
            PresenceContextQueryCoordinator contextQueryCoordinator,
            PresencePartnerStore partnerStore
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.activityTracker = Objects.requireNonNull(activityTracker, "activityTracker");
        this.contextQueryCoordinator = Objects.requireNonNull(contextQueryCoordinator, "contextQueryCoordinator");
        this.partnerStore = Objects.requireNonNull(partnerStore, "partnerStore");
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(adapter, activityTracker, contextQueryCoordinator, partnerStore));
    }
}
