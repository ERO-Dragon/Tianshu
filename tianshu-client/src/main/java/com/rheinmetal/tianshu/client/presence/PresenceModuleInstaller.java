package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.context.PresenceContextQueryCoordinator;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.client.presence.status.PresenceActivityTracker;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

public final class PresenceModuleInstaller implements TianshuModuleInstaller {
    private final PresenceProtocolAdapter adapter;
    private final PresenceStateStore stateStore;
    private final PresenceActivityTracker activityTracker;
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;
    private final PresenceContextQueryCoordinator contextQueryCoordinator;

    public PresenceModuleInstaller(
            PresenceProtocolAdapter adapter,
            PresenceStateStore stateStore,
            PresenceActivityTracker activityTracker,
            PresenceDisplayPolicy displayPolicy,
            PresenceContextFactMapper contextFactMapper,
            PresenceContextQueryCoordinator contextQueryCoordinator
    ) {
        this.adapter = adapter;
        this.stateStore = stateStore;
        this.activityTracker = activityTracker;
        this.displayPolicy = displayPolicy;
        this.contextFactMapper = contextFactMapper == null ? new PresenceContextFactMapper() : contextFactMapper;
        this.contextQueryCoordinator = contextQueryCoordinator;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(adapter, stateStore, activityTracker, displayPolicy, contextFactMapper, contextQueryCoordinator));
    }
}
