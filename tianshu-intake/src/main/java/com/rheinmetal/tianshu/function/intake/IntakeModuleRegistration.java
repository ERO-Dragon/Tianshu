package com.rheinmetal.tianshu.function.intake;

import com.rheinmetal.tianshu.api.assembly.KeywordTableReloadPort;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import com.rheinmetal.tianshu.function.ia.IaModuleProvider;
import com.rheinmetal.tianshu.function.intake.repair.core.IntentKeywordReloader;

/**
 * What this feature announces to whichever loader is starting.
 *
 * <p>Two shells call it — {@code @Mod} on NeoForge, {@code ModInitializer} on Fabric — and this is the only
 * list of what the module contributes. Keeping the announcement here is what makes the pair impossible to
 * drift: a provider added for one loader is added for both, because there is nothing to add it twice.</p>
 *
 * <p>This jar carries two modules: the intake pipeline itself and the arbitration module, which is why two
 * providers are registered here rather than one.</p>
 *
 * <p>The keyword port is filled at the same moment because the platform needs it before a world session
 * exists — the table has to be reloadable when the game reloads resources — so it cannot wait for assembly.</p>
 */
public final class IntakeModuleRegistration {

    private IntakeModuleRegistration() {
    }

    /** Registers this module's ports and providers; called once per loader, before any session exists. */
    public static void announce() {
        TianshuRuntimePorts.register(KeywordTableReloadPort.class, new IntentKeywordReloader());
        TianshuModuleProviderRegistry.register(IntakeModuleProvider::new);
        TianshuModuleProviderRegistry.register(IaModuleProvider::new);
    }
}
