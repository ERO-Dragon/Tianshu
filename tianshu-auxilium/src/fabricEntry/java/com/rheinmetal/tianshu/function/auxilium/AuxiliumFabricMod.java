package com.rheinmetal.tianshu.function.auxilium;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the auxilium feature.
 *
 * <p>The counterpart of {@code AuxiliumNeoForgeMod}: both call {@link AuxiliumModuleRegistration#announce()},
 * the single list of what this module contributes. A loader needs a class to call before it considers the
 * mod real, and that is all this class is.</p>
 */
public final class AuxiliumFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        AuxiliumModuleRegistration.announce();
    }
}
