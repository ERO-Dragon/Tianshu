package com.rheinmetal.tianshu.core.module;

public interface TianshuManagedModule {
    String moduleId();

    default void register(ModuleRegistrationContext context) {}

    default void prepare(ModuleRuntimeContext context) {}

    default void start(ModuleRuntimeContext context) {}

    default void stop() {}

    default void destroy() {}
}
