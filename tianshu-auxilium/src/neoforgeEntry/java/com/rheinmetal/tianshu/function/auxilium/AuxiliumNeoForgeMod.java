package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint for the auxilium feature.
 *
 * <p>Its only job is to announce the module: a loader needs a class to call before it considers the mod
 * real, and that is the moment to register the provider. All behaviour lives in the platform-neutral
 * module; this class never grows logic.</p>
 */
@Mod("tianshu_auxilium")
public final class AuxiliumNeoForgeMod {

    public AuxiliumNeoForgeMod() {
        TianshuModuleProviderRegistry.register(AuxiliumModuleProvider::new);
    }
}
