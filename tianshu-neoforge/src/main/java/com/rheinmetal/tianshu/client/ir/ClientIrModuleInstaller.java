package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.IrModule;
import com.rheinmetal.tianshu.function.ir.IrModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class ClientIrModuleInstaller extends IrModuleInstaller {
    public ClientIrModuleInstaller(ModuleRuntimeAccess moduleRuntime) {
        super(moduleRuntime);
    }

    @Override
    protected IrModule createModule() {
        return new IrModule(moduleRuntime, new ClientIrNamedObjectEnhancer());
    }
}
