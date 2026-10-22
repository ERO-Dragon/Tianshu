package com.rheinmetal.tianshu.function.weaving;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint for the weaving feature.
 *
 * <p>It exists for one reason: a loader needs a class to call before it considers the mod real.
 * All behaviour lives in the platform-neutral module; this class never grows logic.</p>
 */
public final class WeavingFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
    }
}
