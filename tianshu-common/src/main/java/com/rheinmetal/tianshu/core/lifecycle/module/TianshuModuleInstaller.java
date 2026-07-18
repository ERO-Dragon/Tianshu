package com.rheinmetal.tianshu.core.lifecycle.module;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;

public interface TianshuModuleInstaller {
    void install(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices);
}
