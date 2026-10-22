package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The installer set is decided by the provider list, not by the assembler.
 *
 * <p>These tests pin the property that makes optional modules real: an empty list installs nothing,
 * and each provider contributes exactly what it adds. Before this contract existed the assembler
 * named every module, so "not installed" had no representation.</p>
 */
class ClientTianshuModuleAssemblerTest {

    @Test
    void emptyProviderListInstallsNoModules() {
        ClientTianshuModuleAssembler assembler = new ClientTianshuModuleAssembler(List.of());

        TianshuModuleHost host = new TianshuModuleHost(new FakeGameEnvironment());
        assembler.assemble(host, new ModuleServiceRegistry());

        assertTrue(host.managedModules().isEmpty(), "no provider means no module");
    }

    @Test
    void eachProviderContributesItsOwnInstaller() {
        TianshuModuleProvider first = registrar -> registrar.addModule(noopModule("module.first"));
        TianshuModuleProvider second = registrar -> registrar.addModule(noopModule("module.second"));
        ClientTianshuModuleAssembler assembler = new ClientTianshuModuleAssembler(List.of(first, second));

        TianshuModuleHost host = new TianshuModuleHost(new FakeGameEnvironment());
        assembler.assemble(host, new ModuleServiceRegistry());

        assertEquals(
                List.of("module.first", "module.second"),
                host.managedModules().stream().map(module -> module.moduleId()).sorted().toList()
        );
    }

    private static com.rheinmetal.tianshu.core.lifecycle.module.TianshuModuleInstaller noopModule(String moduleId) {
        return (host, services) -> host.registerOptionalModule(new com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule() {
            @Override
            public String moduleId() {
                return moduleId;
            }
        });
    }
}
