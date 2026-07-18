package com.rheinmetal.tianshu.function.ir;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public class IrModuleInstaller implements TianshuModuleInstaller {
    protected final ModuleRuntimeAccess moduleRuntime;

    public IrModuleInstaller(ModuleRuntimeAccess moduleRuntime) {
        this.moduleRuntime = moduleRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(createModule());
    }

    protected IrModule createModule() {
        return new IrModule(moduleRuntime);
    }
}
