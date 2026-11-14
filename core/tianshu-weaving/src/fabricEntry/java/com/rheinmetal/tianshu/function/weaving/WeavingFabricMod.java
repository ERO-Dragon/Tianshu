package com.rheinmetal.tianshu.function.weaving;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the weaving feature.
 *
 * <p>The counterpart of {@code WeavingNeoForgeMod}: both call {@link WeavingModuleRegistration#announce()},
 * the single list of what this module contributes. A loader needs a class to call before it considers the
 * mod real, and that is all this class is.</p>
 */
public final class WeavingFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        WeavingModuleRegistration.announce();
    }
}
