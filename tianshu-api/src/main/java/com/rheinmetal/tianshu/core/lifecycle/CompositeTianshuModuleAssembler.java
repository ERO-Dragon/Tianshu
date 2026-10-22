package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

import java.util.List;

public final class CompositeTianshuModuleAssembler implements TianshuModuleAssembler {
    private final List<TianshuModuleInstaller> installers;

    public CompositeTianshuModuleAssembler(List<TianshuModuleInstaller> installers) {
        this.installers = List.copyOf(installers);
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.clear();
        moduleServices.clear();

        for (TianshuModuleInstaller installer : installers) {
            installer.install(moduleHost, moduleServices);
        }
    }
}
