package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsSourceRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.core.lifecycle.CompositeTianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssemblyContext;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderFactory;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns providers into the installers one session runs.
 *
 * <p>Two sources feed it: the providers the kernel owns itself (the host's own platform modules, which
 * ship with it), and the factories modules registered from their own jars. Because the second set is
 * discovered rather than listed, removing a module's jar removes its installer from the session without
 * editing the kernel — and its settings page disappears through the same registrar.</p>
 */
public final class ClientTianshuModuleAssembler implements TianshuModuleAssembler {

    private final List<TianshuModuleProvider> kernelProviders;
    private final TianshuModuleAssemblyContext context;
    private final ModuleSettingsSourceRegistry settingsSources;

    public ClientTianshuModuleAssembler(List<TianshuModuleProvider> kernelProviders,
                                        TianshuModuleAssemblyContext context,
                                        ModuleSettingsSourceRegistry settingsSources) {
        this.kernelProviders = List.copyOf(kernelProviders);
        this.context = context;
        this.settingsSources = settingsSources;
    }

    /** Kernel providers only; used by headless assembly and by tests that stub the module set. */
    public ClientTianshuModuleAssembler(List<TianshuModuleProvider> kernelProviders) {
        this(kernelProviders, null, new ModuleSettingsSourceRegistry());
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        List<TianshuModuleInstaller> installers = new ArrayList<>();
        if (settingsSources != null) {
            settingsSources.clear();
        }
        TianshuModuleRegistrar registrar = new TianshuModuleRegistrar() {
            @Override
            public void addModule(TianshuModuleInstaller installer) {
                if (installer != null) {
                    installers.add(installer);
                }
            }

            @Override
            public void addSettings(TianshuSettingsRegistrySource source) {
                if (settingsSources != null) {
                    settingsSources.add(source);
                }
            }
        };

        for (TianshuModuleProvider provider : kernelProviders) {
            provider.contribute(registrar);
        }
        for (TianshuModuleProviderFactory factory : TianshuModuleProviderRegistry.factories()) {
            TianshuModuleProvider provider = factory.create(context);
            if (provider != null) {
                provider.contribute(registrar);
            }
        }
        new CompositeTianshuModuleAssembler(installers).assemble(moduleHost, moduleServices);
        if (context != null && context.env() != null) {
            context.env().logs().info("neoforge.modules.assembled installers=" + installers.size()
                    + " settings=" + (settingsSources == null ? 0 : settingsSources.sources().size())
                    + " providers=" + (kernelProviders.size()
                            + TianshuModuleProviderRegistry.factories().size()));
        }
    }
}
