package com.rheinmetal.tianshu.function.ui;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.event.TianshuEventBus;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class UiProtocolBridgeInstaller implements TianshuFunctionModuleInstaller {
    private final ProtocolRuntime protocolRuntime;
    private final TianshuEventBus eventBus;

    public UiProtocolBridgeInstaller(ProtocolRuntime protocolRuntime, TianshuEventBus eventBus) {
        this.protocolRuntime = protocolRuntime;
        this.eventBus = eventBus;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new UiProtocolBridge(protocolRuntime, eventBus));
    }
}
