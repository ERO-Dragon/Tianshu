package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.core.lifecycle.CompositeTianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleAssembler;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleRegistrar;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a list of providers into the installers one session runs.
 *
 * <p>This class used to name every module and build its installer directly, which made "the module is
 * not installed" impossible to express — the code path that installed it was always present. It now
 * only knows the provider interface, so the set of modules is decided once, at the composition root.</p>
 */
public final class ClientTianshuModuleAssembler implements TianshuModuleAssembler {

    private final List<TianshuModuleProvider> providers;

    public ClientTianshuModuleAssembler(List<TianshuModuleProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public void assemble(TianshuModuleHost moduleHost, ModuleServiceRegistry moduleServices) {
        List<TianshuModuleInstaller> installers = new ArrayList<>();
        TianshuModuleRegistrar registrar = installers::add;
        for (TianshuModuleProvider provider : providers) {
            provider.contribute(registrar);
        }
        new CompositeTianshuModuleAssembler(installers).assemble(moduleHost, moduleServices);
    }
}
