package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;

/**
 * What this feature announces to whichever loader is starting.
 *
 * <p>Two shells call it — {@code @Mod} on NeoForge, {@code ModInitializer} on Fabric — and this is the only
 * list of what the module contributes. Keeping the announcement here is what makes the pair impossible to
 * drift: a provider added for one loader is added for both, because there is nothing to add it twice.</p>
 *
 * <p>The HUD shader declarations belong here for the same reason: they must exist before the client's shader event,
 * and declaring them is inert until the platform creates the programs.</p>
 */
public final class PresenceModuleRegistration {

    private PresenceModuleRegistration() {
    }

    /** Registers this module's providers and HUD shader programs; called once per loader, before any session exists. */
    public static void announce() {
        PresenceHudShaders.declare();
        TianshuModuleProviderRegistry.register(PresenceModuleProvider::new);
    }
}
