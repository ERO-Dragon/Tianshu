package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextFactMapper;
import com.rheinmetal.tianshu.client.presence.status.PresenceDisplayPolicy;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;

public final class PresenceModuleInstaller implements TianshuFunctionModuleInstaller {
    private final PresenceProtocolAdapter adapter;
    private final PresenceStateStore stateStore;
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;

    public PresenceModuleInstaller(
            PresenceProtocolAdapter adapter,
            PresenceStateStore stateStore,
            PresenceDisplayPolicy displayPolicy,
            PresenceContextFactMapper contextFactMapper
    ) {
        this.adapter = adapter;
        this.stateStore = stateStore;
        this.displayPolicy = displayPolicy;
        this.contextFactMapper = contextFactMapper == null ? new PresenceContextFactMapper() : contextFactMapper;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(adapter, stateStore, displayPolicy, contextFactMapper));
    }
}
