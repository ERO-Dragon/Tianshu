package com.rheinmetal.tianshu.function.presence;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge shell for the presence feature.
 *
 * <p>Its only job is to be constructed: a loader needs a class before it considers the mod real, and that is
 * the moment the module announces itself. What gets announced lives in {@link PresenceModuleRegistration},
 * shared with the Fabric shell, so this class never grows logic.</p>
 */
@Mod("tianshu_presence")
public final class PresenceNeoForgeMod {

    public PresenceNeoForgeMod() {
        PresenceModuleRegistration.announce();
    }
}
