package com.rheinmetal.tianshu.function;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;

public interface TianshuFunctionModuleInstaller {
    void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices);
}
