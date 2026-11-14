package com.rheinmetal.tianshu.core.lifecycle;

@FunctionalInterface
public interface TianshuModuleAssemblerFactory {
    TianshuModuleAssembler create(TianshuModuleAssemblyContext context);
}
