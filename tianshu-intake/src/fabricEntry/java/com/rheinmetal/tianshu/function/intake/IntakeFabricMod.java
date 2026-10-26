package com.rheinmetal.tianshu.function.intake;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the intake feature.
 *
 * <p>The counterpart of {@code IntakeNeoForgeMod}: both call {@link IntakeModuleRegistration#announce()},
 * the single list of what this module contributes. A loader needs a class to call before it considers the
 * mod real, and that is all this class is.</p>
 */
public final class IntakeFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        IntakeModuleRegistration.announce();
    }
}
