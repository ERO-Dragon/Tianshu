package com.rheinmetal.tianshu.function.echo;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the echo feature.
 *
 * <p>The counterpart of {@code EchoNeoForgeMod}: both call {@link EchoModuleRegistration#announce()}, the
 * single list of what this module contributes. A loader needs a class to call before it considers the mod
 * real, and that is all this class is.</p>
 */
public final class EchoFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        EchoModuleRegistration.announce();
    }
}
