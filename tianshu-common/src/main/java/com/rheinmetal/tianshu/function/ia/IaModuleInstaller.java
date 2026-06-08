package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextProvider;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class IaModuleInstaller implements TianshuFunctionModuleInstaller {
    private final ProtocolRuntime protocolRuntime;
    private final DialogueContextProvider contextProvider;

    public IaModuleInstaller(ProtocolRuntime protocolRuntime) {
        this(protocolRuntime, DialogueContextProvider.EMPTY);
    }

    public IaModuleInstaller(ProtocolRuntime protocolRuntime, DialogueContextProvider contextProvider) {
        this.protocolRuntime = protocolRuntime;
        this.contextProvider = contextProvider == null ? DialogueContextProvider.EMPTY : contextProvider;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new IaModule(protocolRuntime, contextProvider), IaRuntimeCapabilities.ARBITRATION);
    }
}
