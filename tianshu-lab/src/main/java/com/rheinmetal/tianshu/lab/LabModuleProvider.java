package com.rheinmetal.tianshu.lab;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;

/**
 * Announces the bench to the session.
 *
 * <p>The development client contributes the arbitration UI and participants once its module runtime exists.</p>
 */
public final class LabModuleProvider implements TianshuModuleProvider {
    private final TianshuModuleAssemblyContext context;

    public LabModuleProvider(TianshuModuleAssemblyContext context) {
        this.context = context;
    }

    @Override
    public void contribute(TianshuModuleRegistrar registrar) {
        if (context == null || context.moduleRuntime() == null) {
            return;
        }
        ClientPorts ports = context.hasClientPorts() ? context.clientPorts() : null;
        LabModule module = new LabModule(context.moduleRuntime(), ports);
        registrar.addModule((host, services) -> host.registerOptionalModule(module));
        if (ports != null) {
            registrar.addSettings(new LabSettingsSource(module,
                    context.env() == null ? com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink.NOOP
                            : context.env().diagnostics()));
        }
    }
}