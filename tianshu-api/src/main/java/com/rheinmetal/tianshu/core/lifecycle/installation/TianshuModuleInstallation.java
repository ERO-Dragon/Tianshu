package com.rheinmetal.tianshu.core.lifecycle.installation;

import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapability;

import java.util.List;
import java.util.Objects;

public record TianshuModuleInstallation(
        TianshuManagedModule module,
        ModuleFailurePolicy failurePolicy,
        List<RuntimeCapability> providedCapabilities
) {
    public TianshuModuleInstallation {
        Objects.requireNonNull(module, "module");
        failurePolicy = failurePolicy == null ? ModuleFailurePolicy.OPTIONAL : failurePolicy;
        providedCapabilities = providedCapabilities == null ? List.of() : List.copyOf(providedCapabilities);
    }

    public static TianshuModuleInstallation optional(TianshuManagedModule module) {
        return new TianshuModuleInstallation(module, ModuleFailurePolicy.OPTIONAL, List.of());
    }

    public static TianshuModuleInstallation optional(TianshuManagedModule module, RuntimeCapability... providedCapabilities) {
        return new TianshuModuleInstallation(module, ModuleFailurePolicy.OPTIONAL, capabilities(providedCapabilities));
    }

    public static TianshuModuleInstallation required(TianshuManagedModule module) {
        return new TianshuModuleInstallation(module, ModuleFailurePolicy.REQUIRED, List.of());
    }

    public static TianshuModuleInstallation required(TianshuManagedModule module, RuntimeCapability... providedCapabilities) {
        return new TianshuModuleInstallation(module, ModuleFailurePolicy.REQUIRED, capabilities(providedCapabilities));
    }

    public String moduleId() {
        return module.moduleId();
    }

    public boolean required() {
        return failurePolicy == ModuleFailurePolicy.REQUIRED;
    }

    private static List<RuntimeCapability> capabilities(RuntimeCapability... capabilities) {
        if (capabilities == null || capabilities.length == 0) {
            return List.of();
        }
        return List.of(capabilities);
    }
}
