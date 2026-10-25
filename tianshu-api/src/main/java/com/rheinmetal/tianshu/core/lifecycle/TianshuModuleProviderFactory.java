package com.rheinmetal.tianshu.core.lifecycle;

/**
 * Turns a session's assembly context into a provider.
 *
 * <p>A module registers one of these while its jar is loading, before any session exists. The kernel
 * calls it per session, so the provider can build that session's config, installer and settings page
 * from the ports the kernel offers — without the kernel knowing the module's name.</p>
 */
@FunctionalInterface
public interface TianshuModuleProviderFactory {

    TianshuModuleProvider create(TianshuModuleAssemblyContext context);
}
