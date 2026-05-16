package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class IaModuleInstaller implements TianshuFunctionModuleInstaller {
    private final ProtocolRuntime protocolRuntime;

    public IaModuleInstaller(ProtocolRuntime protocolRuntime) {
        this.protocolRuntime = protocolRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new IaModule(protocolRuntime), IaRuntimeCapabilities.ARBITRATION);
    }
}
