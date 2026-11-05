package com.rheinmetal.tianshu.function.echo;

import net.minecraftforge.fml.common.Mod;

/**
 * Legacy Forge shell, for Minecraft 1.20.1-1.20.3 only.
 *
 * <p>Those versions read `META-INF/mods.toml` and scan for `net.minecraftforge.fml.common.Mod`; modern NeoForge
 * reads `META-INF/neoforge.mods.toml` and scans for `net.neoforged.fml.common.Mod`, so this class is invisible
 * there and {@link EchoNeoForgeMod} is invisible here. Both call {@link EchoModuleRegistration#announce()},
 * which keeps the two shells from drifting.</p>
 */
@Mod("tianshu_echo")
public final class EchoForgeMod {

    public EchoForgeMod() {
        EchoModuleRegistration.announce();
    }
}