package com.rheinmetal.tianshu.core.lifecycle;

import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

/**
 * Collects what a {@link TianshuModuleProvider} wants the session to run.
 *
 * <p>Deliberately minimal: a provider contributes modules, and nothing else has to be registered
 * through this door yet. Adding a method here is a contract change for every provider, so methods
 * are added when a contributor actually needs them.</p>
 */
public interface TianshuModuleRegistrar {

    void addModule(TianshuModuleInstaller installer);
}
