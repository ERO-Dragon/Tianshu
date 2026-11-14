package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

/**
 * Collects what a {@link TianshuModuleProvider} wants the session to run.
 *
 * <p>Deliberately minimal: a provider contributes modules and, if it has any, the settings pages that
 * belong to them. Both go through this one door, so a module's page cannot take a privileged path that
 * an external integration does not have.</p>
 *
 * <p>Adding a method here is a contract change for every provider, so methods are added when a
 * contributor actually needs them.</p>
 */
public interface TianshuModuleRegistrar {

    void addModule(TianshuModuleInstaller installer);

    /** The module ships its own settings page; the kernel only places it in the settings screen. */
    void addSettings(TianshuSettingsRegistrySource source);
}
