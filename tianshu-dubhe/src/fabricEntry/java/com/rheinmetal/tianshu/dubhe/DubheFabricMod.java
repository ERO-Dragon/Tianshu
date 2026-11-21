package com.rheinmetal.tianshu.dubhe;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the auxilium feature.
 *
 * <p>The counterpart of {@code DubheNeoForgeMod}: both call {@link DubheModuleRegistration#announce()},
 * the single list of what this module contributes. A loader needs a class to call before it considers the
 * mod real, and that is all this class is.</p>
 */
public final class DubheFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        DubheModuleRegistration.announce();
    }
}
