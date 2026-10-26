package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
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

    public PresenceModuleInstaller(
            PresenceProtocolAdapter adapter,
            PresenceActivityTracker activityTracker,
            PresenceContextQueryCoordinator contextQueryCoordinator
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.activityTracker = Objects.requireNonNull(activityTracker, "activityTracker");
        this.contextQueryCoordinator = Objects.requireNonNull(contextQueryCoordinator, "contextQueryCoordinator");
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(adapter, activityTracker, contextQueryCoordinator));
    }
}
