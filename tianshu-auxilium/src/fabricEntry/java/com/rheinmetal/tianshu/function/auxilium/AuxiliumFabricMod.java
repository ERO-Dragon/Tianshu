package com.rheinmetal.tianshu.function.auxilium;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint for the auxilium feature.
 *
 * <p>It exists for one reason: a loader needs a class to call before it considers the mod real.
 * All behaviour lives in the platform-neutral module; this class never grows logic.</p>
 */
public final class AuxiliumFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
    }
}
