package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;

import java.util.List;

public final class CompositeTianshuFunctionModuleAssembler implements TianshuModuleAssembler {
    private final List<TianshuFunctionModuleInstaller> installers;

    public CompositeTianshuFunctionModuleAssembler(List<TianshuFunctionModuleInstaller> installers) {
        this.installers = List.copyOf(installers);
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.clear();
        moduleServices.clear();

        for (TianshuFunctionModuleInstaller installer : installers) {
            installer.install(moduleHost, moduleServices);
        }
    }
}
