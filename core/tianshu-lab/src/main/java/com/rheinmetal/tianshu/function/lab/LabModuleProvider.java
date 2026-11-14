package com.rheinmetal.tianshu.function.lab;

import com.rheinmetal.tianshu.api.assembly.ClientPorts;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;

/**
 * Announces the bench to the session.
 *
 * <p>Only the development client installs this mod, so the provider never has to decide whether it should
 * exist: the jar's presence is the answer. It still checks the runtime context because a host without
 * protocol access cannot host a participant at all.</p>
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