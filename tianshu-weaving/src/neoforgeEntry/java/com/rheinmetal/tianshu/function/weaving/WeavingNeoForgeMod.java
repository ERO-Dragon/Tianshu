package com.rheinmetal.tianshu.function.weaving;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint for the weaving feature.
 *
 * <p>It exists for one reason: a loader needs a class to call before it considers the mod real.
 * All behaviour lives in the platform-neutral module; this class never grows logic.</p>
 */
@Mod("tianshu_weaving")
public final class WeavingNeoForgeMod {
}
