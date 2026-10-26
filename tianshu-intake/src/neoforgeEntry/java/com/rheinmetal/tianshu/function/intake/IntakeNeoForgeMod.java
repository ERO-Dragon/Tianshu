package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.api.assembly.KeywordTableReloadPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import com.rheinmetal.tianshu.function.intake.repair.core.IntentKeywordReloader;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entrypoint for the intake feature.
 *
 * <p>It announces the module and fills the one port the platform needs before any world session exists:
 * the keyword table has to be reloadable when the game reloads resources. All behaviour lives in the
 * platform-neutral module; this class never grows logic.</p>
 */
@Mod("tianshu_intake")
public final class IntakeNeoForgeMod {

    public IntakeNeoForgeMod() {
        TianshuRuntimePorts.register(KeywordTableReloadPort.class, new IntentKeywordReloader());
        TianshuModuleProviderRegistry.register(IntakeModuleProvider::new);
        TianshuModuleProviderRegistry.register(com.rheinmetal.tianshu.function.ia.IaModuleProvider::new);
    }
}
