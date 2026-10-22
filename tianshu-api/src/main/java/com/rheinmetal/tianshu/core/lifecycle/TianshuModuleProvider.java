package com.rheinmetal.tianshu.core.lifecycle;

/**
 * Something that adds optional modules to a session.
 *
 * <p>The core never enumerates modules itself: whoever composes the runtime hands in a list of these,
 * and each one contributes its installers. An absent provider therefore means an absent module —
 * there is no hard-coded list that would install it anyway.</p>
 *
 * <p>A provider is a plain lambda in practice ({@code r -> r.addModule(new XInstaller(...))}); the
 * interface exists so the composition root can express that without the core knowing module names.</p>
 */
@FunctionalInterface
public interface TianshuModuleProvider {

    /**
     * @param registrar collects the installers this provider wants the session to run
     */
    void contribute(TianshuModuleRegistrar registrar);
}
