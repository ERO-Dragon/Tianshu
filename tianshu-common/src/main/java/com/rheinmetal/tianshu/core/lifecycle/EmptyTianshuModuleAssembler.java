package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;

public final class EmptyTianshuModuleAssembler implements TianshuModuleAssembler {
    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        moduleHost.clear();
        moduleServices.clear();
    }
}
