package com.rheinmetal.tianshu.function.auxilium;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge shell for the auxilium feature.
 *
 * <p>Its only job is to be constructed: a loader needs a class before it considers the mod real, and that is
 * the moment the module announces itself. What gets announced lives in {@link AuxiliumModuleRegistration},
 * shared with the Fabric shell, so this class never grows logic.</p>
 */
@Mod("tianshu_auxilium")
public final class AuxiliumNeoForgeMod {

    public AuxiliumNeoForgeMod() {
        AuxiliumModuleRegistration.announce();
    }
}
