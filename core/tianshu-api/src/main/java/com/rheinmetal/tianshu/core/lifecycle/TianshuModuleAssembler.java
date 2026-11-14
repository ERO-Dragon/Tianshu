package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;

public interface TianshuModuleAssembler {
    void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices);
}
