package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderFactory;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProviderRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery half of optional modules: a jar announces its factory, the kernel reads the list.
 *
 * <p>The assembler tests next door pin that the provider <em>list</em> decides the installer set. What they
 * cannot show is the other direction — that a module which registered itself from its own jar is picked up
 * without the kernel naming it, and that an unregistered one leaves no trace. That property is the whole
 * reason a module can be removed by deleting its jar.</p>
 *
 * <p>The registry is process-wide static state, so every test here restores it. A leaked registration would
 * make {@code emptyProviderListInstallsNoModules} fail depending on test order, which is a worse failure
 * than the one these tests prevent.</p>
 */
class ModuleProviderDiscoveryTest {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        TianshuModuleProviderRegistry.clear();
    }

    @Test
    void aFactoryRegisteredWhileAJarLoadsBecomesAnInstaller() {
        TianshuModuleProviderRegistry.register(context -> registrar -> registrar.addModule(module("module.discovered")));

        TianshuModuleHost host = assembleWithKernelProviders();

        assertEquals(List.of("module.discovered"), managedModuleIds(host),
                "the kernel names no module here; discovery is the only thing that could have added it");
    }

    @Test
    void anUnregisteredFactoryLeavesTheSessionEmpty() {
        TianshuModuleHost host = assembleWithKernelProviders();

        assertTrue(host.managedModules().isEmpty(), "no registered factory means no module");
    }

    @Test
    void clearingTheRegistryRemovesAModuleFromTheNextSession() {
        TianshuModuleProviderRegistry.register(context -> registrar -> registrar.addModule(module("module.discovered")));
        assertEquals(List.of("module.discovered"), managedModuleIds(assembleWithKernelProviders()));

        TianshuModuleProviderRegistry.clear();

        assertTrue(assembleWithKernelProviders().managedModules().isEmpty(),
                "deleting a jar is represented by clearing its factory");
    }

    @Test
    void aFactoryThatDeclinesToContributeIsSkipped() {
        TianshuModuleProviderRegistry.register(context -> null);
        TianshuModuleProviderRegistry.register(context -> registrar -> registrar.addModule(module("module.present")));

        assertEquals(List.of("module.present"), managedModuleIds(assembleWithKernelProviders()),
                "one declining factory must not swallow the others");
    }

    @Test
    void factoriesAreReadOncePerSession() {
        TianshuModuleProviderFactory counting = context -> registrar -> registrar.addModule(module("module.counted"));
        TianshuModuleProviderRegistry.register(counting);

        assembleWithKernelProviders();
        TianshuModuleHost second = assembleWithKernelProviders();

        assertEquals(List.of("module.counted"), managedModuleIds(second),
                "a second session still sees the registered module, and only once");
    }

    // ---------------------------------------------------------------- fixtures

    private static TianshuModuleHost assembleWithKernelProviders() {
        TianshuModuleHost host = new TianshuModuleHost(new FakeGameEnvironment());
        new ClientTianshuModuleAssembler(List.of()).assemble(host, new ModuleServiceRegistry());
        return host;
    }

    private static List<String> managedModuleIds(TianshuModuleHost host) {
        return host.managedModules().stream().map(TianshuManagedModule::moduleId).sorted().toList();
    }

    private static TianshuModuleInstaller module(String moduleId) {
        return (host, services) -> host.registerOptionalModule(new TianshuManagedModule() {
            @Override
            public String moduleId() {
                return moduleId;
            }
        });
    }
}
