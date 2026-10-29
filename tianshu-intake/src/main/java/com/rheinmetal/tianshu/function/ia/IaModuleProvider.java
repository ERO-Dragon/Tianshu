package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerConfig;
import com.rheinmetal.tianshu.function.ia.settings.IaDefaultOwnerSettings;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;

/**
 * Speech (Intake) announces the arbitration brain to the kernel.
 *
 * <p>Arbitration exists because speech has no addressing: several participants hear the same utterance and
 * the system has to decide who it was meant for. It therefore ships with the speech module instead of the
 * kernel — with no speech module there is nothing to arbitrate, and the kernel keeps no reference to it.</p>
 *
 * <p>The default-owner setting is read from this module's own config store, the same store the settings page
 * writes to. Both sides wrap the same keys, so the page and the arbiter cannot drift apart.</p>
 */
public final class IaModuleProvider implements TianshuModuleProvider {

    private final TianshuModuleAssemblyContext context;

    public IaModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || context.moduleRuntime() == null) {
            return;
        }
        IaDefaultOwnerSettings settings = IaDefaultOwnerSettings.UNCONFIGURED;
        if (context.hasClientPorts()) {
            settings = new IaDefaultOwnerConfig(context.clientPorts().configStores().forModule(ProtocolModuleIds.IA));
        }
        registrar.addModule(new IaModuleInstaller(context.moduleRuntime(), settings));
    }
}