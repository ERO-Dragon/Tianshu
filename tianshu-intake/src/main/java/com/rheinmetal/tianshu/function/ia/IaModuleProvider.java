package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;

/**
 * Speech (Intake) announces the arbitration brain to the kernel.
 *
 * <p>Arbitration exists because speech has no addressing: several participants hear the same utterance and
 * the system has to decide who it was meant for. It therefore ships with the speech module instead of the
 * kernel — with no speech module there is nothing to arbitrate, and the kernel keeps no reference to it.</p>
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
        registrar.addModule(new IaModuleInstaller(context.moduleRuntime()));
    }
}
