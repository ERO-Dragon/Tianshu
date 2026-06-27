package com.rheinmetal.tianshu.client.presence;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class PresenceModuleInstaller implements TianshuFunctionModuleInstaller {
    private final ProtocolRuntime protocolRuntime;
    private final PresenceStateStore stateStore;
    private final PresenceDisplayPolicy displayPolicy;
    private final PresenceContextFactMapper contextFactMapper;

    public PresenceModuleInstaller(
            ProtocolRuntime protocolRuntime,
            PresenceStateStore stateStore,
            PresenceDisplayPolicy displayPolicy,
            PresenceContextFactMapper contextFactMapper
    ) {
        this.protocolRuntime = protocolRuntime;
        this.stateStore = stateStore;
        this.displayPolicy = displayPolicy;
        this.contextFactMapper = contextFactMapper == null ? new PresenceContextFactMapper() : contextFactMapper;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new PresenceModule(protocolRuntime, stateStore, displayPolicy, contextFactMapper));
    }
}
