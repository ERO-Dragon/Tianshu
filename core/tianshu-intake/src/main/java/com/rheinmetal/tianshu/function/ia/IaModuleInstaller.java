package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.runtime.ModuleRuntimeAccess;

public final class IaModuleInstaller implements TianshuModuleInstaller {
    private final ModuleRuntimeAccess moduleRuntime;
    private final IaDefaultOwnerSettings defaultOwnerSettings;

    public IaModuleInstaller(ModuleRuntimeAccess moduleRuntime) {
        this(moduleRuntime, IaDefaultOwnerSettings.UNCONFIGURED);
    }

    public IaModuleInstaller(ModuleRuntimeAccess moduleRuntime, IaDefaultOwnerSettings defaultOwnerSettings) {
        this.moduleRuntime = moduleRuntime;
        this.defaultOwnerSettings = defaultOwnerSettings == null
                ? IaDefaultOwnerSettings.UNCONFIGURED
                : defaultOwnerSettings;
    }

    @Override
    public void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.registerOptionalModule(new IaModule(moduleRuntime, defaultOwnerSettings), IaRuntimeCapabilities.ARBITRATION);
    }
}