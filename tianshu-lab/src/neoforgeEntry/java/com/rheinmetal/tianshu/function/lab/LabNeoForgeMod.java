package com.rheinmetal.tianshu.function.lab;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge shell for the test bench: being constructed is the whole job.
 */
@Mod("tianshu_lab")
public final class LabNeoForgeMod {

    public LabNeoForgeMod() {
        LabModuleRegistration.announce();
    }
}