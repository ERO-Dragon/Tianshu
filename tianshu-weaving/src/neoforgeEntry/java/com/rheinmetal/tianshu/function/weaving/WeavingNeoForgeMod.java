package com.rheinmetal.tianshu.function.weaving;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint for the weaving feature.
 *
 * <p>Its only job is to announce the module: a loader needs a class to call before it considers the mod
 * real, and that is the moment to register the provider. All behaviour lives in the platform-neutral
 * module; this class never grows logic.</p>
 */
@Mod("tianshu_weaving")
public final class WeavingNeoForgeMod {

    public WeavingNeoForgeMod() {
        TianshuModuleProviderRegistry.register(WeavingModuleProvider::new);
    }
}
