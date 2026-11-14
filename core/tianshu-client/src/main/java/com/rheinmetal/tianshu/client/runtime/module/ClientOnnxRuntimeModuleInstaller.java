package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

public final class ClientOnnxRuntimeModuleInstaller implements TianshuModuleInstaller {
    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        // ONNX is an optional capability. Native linkage failures must not prevent
        // unrelated core modules (for example LLM and IR) from starting.
        moduleHost.registerOptionalModule(new ClientOnnxRuntimeModule());
    }
}
