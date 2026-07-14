package com.rheinmetal.tianshu.client.lifecycle;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.TianshuFunctionModuleInstaller;

public final class ClientOnnxRuntimeModuleInstaller implements TianshuFunctionModuleInstaller {
    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerRequiredModule(new ClientOnnxRuntimeModule());
    }
}
