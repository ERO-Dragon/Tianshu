package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;

/**
 * What this feature announces to whichever loader is starting.
 *
 * <p>Two shells call it — {@code @Mod} on NeoForge, {@code ModInitializer} on Fabric — and this is the only
 * list of what the module contributes. Keeping the announcement here is what makes the pair impossible to
 * drift: a provider added for one loader is added for both, because there is nothing to add it twice.</p>
 */
public final class PresenceModuleRegistration {

    private PresenceModuleRegistration() {
    }

    /** Registers this module's providers; called once per loader, before any session exists. */
    public static void announce() {
        TianshuModuleProviderRegistry.register(PresenceModuleProvider::new);
    }
}
