package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public class IrModuleInstaller implements TianshuFunctionModuleInstaller {
    protected final ProtocolRuntime protocolRuntime;

    public IrModuleInstaller(ProtocolRuntime protocolRuntime) {
        this.protocolRuntime = protocolRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(createModule());
    }

    protected IrModule createModule() {
        return new IrModule(protocolRuntime);
    }
}
