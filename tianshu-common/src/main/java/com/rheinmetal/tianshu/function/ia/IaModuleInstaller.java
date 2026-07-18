package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class IaModuleInstaller implements TianshuModuleInstaller {
    private final ModuleRuntimeAccess moduleRuntime;

    public IaModuleInstaller(ModuleRuntimeAccess moduleRuntime) {
        this.moduleRuntime = moduleRuntime;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new IaModule(moduleRuntime), IaRuntimeCapabilities.ARBITRATION);
    }
}
