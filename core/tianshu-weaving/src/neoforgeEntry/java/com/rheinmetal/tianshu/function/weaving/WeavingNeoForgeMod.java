package com.rheinmetal.tianshu.function.weaving;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge shell for the weaving feature.
 *
 * <p>Its only job is to be constructed: a loader needs a class before it considers the mod real, and that is
 * the moment the module announces itself. What gets announced lives in {@link WeavingModuleRegistration},
 * shared with the Fabric shell, so this class never grows logic.</p>
 */
@Mod("tianshu_weaving")
public final class WeavingNeoForgeMod {

    public WeavingNeoForgeMod() {
        WeavingModuleRegistration.announce();
    }
}
