package com.rheinmetal.tianshu.function.presence;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the presence feature.
 *
 * <p>The counterpart of {@code PresenceNeoForgeMod}: both call {@link PresenceModuleRegistration#announce()},
 * the single list of what this module contributes. A loader needs a class to call before it considers the
 * mod real, and that is all this class is.</p>
 */
public final class PresenceFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        PresenceModuleRegistration.announce();
    }
}
