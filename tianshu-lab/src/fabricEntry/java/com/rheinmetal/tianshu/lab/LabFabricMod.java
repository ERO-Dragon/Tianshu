package com.rheinmetal.tianshu.lab;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric shell for the test bench: being initialised is the whole job, same as the NeoForge shell.
 */
public final class LabFabricMod implements ModInitializer {

    @Override
    public void onInitialize() {
        LabModuleRegistration.announce();
    }
}