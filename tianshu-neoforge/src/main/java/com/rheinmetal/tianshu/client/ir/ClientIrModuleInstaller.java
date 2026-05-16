package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.IrModule;
import com.rheinmetal.tianshu.function.ir.IrModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;

public final class ClientIrModuleInstaller extends IrModuleInstaller {
    public ClientIrModuleInstaller(ProtocolRuntime protocolRuntime) {
        super(protocolRuntime);
    }

    @Override
    protected IrModule createModule() {
        return new IrModule(protocolRuntime, new ClientIrItemEnhancer());
    }
}
