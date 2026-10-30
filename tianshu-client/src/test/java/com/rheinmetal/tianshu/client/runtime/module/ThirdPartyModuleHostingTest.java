package com.rheinmetal.tianshu.client.runtime.module;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.diagnostics.DiagnosticSink;
import com.rheinmetal.tianshu.client.settings.registry.ModuleSettingsSourceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleProvider;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.lifecycle.module.TianshuManagedModule;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A provider that ships outside this repository is assembled by the same path the kernel's own providers use.
 *
 * <p>This is the contract a third-party module relies on: contribute an installer (and optionally a settings
 * source), and the session runs it without any host edit. The test deliberately uses only public lifecycle types
 * and no Minecraft classpath.</p>
 */
class ThirdPartyModuleHostingTest {

    @Test
    void aProviderOutsideTheKernelIsAssembled() {
        ModuleSettingsSourceRegistry settings = new ModuleSettingsSourceRegistry();
        TianshuModuleProvider thirdParty = registrar -> {
            registrar.addModule((host, services) -> host.registerModule(new TianshuManagedModule() {
                @Override
                public String moduleId() {
                    return "module.thirdparty";
                }
            }));
            registrar.addSettings((registry, context) -> {
            });
        };

        TianshuModuleHost host = new TianshuModuleHost(new QuietEnvironment());
        new ClientTianshuModuleAssembler(List.of(thirdParty), null, settings)
                .assemble(host, new ModuleServiceRegistry());

        assertEquals(List.of("module.thirdparty"),
                host.managedModules().stream().map(TianshuManagedModule::moduleId).toList(),
                "the contributed module must reach the session host");
        assertEquals(1, settings.sources().size(), "the contributed settings page must be visible to the screen");
        assertEquals(List.of("module.thirdparty"),
                host.moduleStatuses().stream().map(status -> status.moduleId()).toList(),
                "the host must track the module's declared status");
        assertTrue(host.managedModules().size() == 1);
    }

    private static final class QuietEnvironment implements IGameEnvironment {
        @Override
        public void displayMessageToPlayer(String message) {
        }

        @Override
        public void executeOnMainThread(Runnable task) {
            task.run();
        }

        @Override
        public Path getGameDirectory() {
            return Path.of(".");
        }

        @Override
        public boolean isClientSide() {
            return true;
        }

        @Override
        public void openFolder(Path dir) {
        }

        @Override
        public void info(String msg) {
        }

        @Override
        public void warn(String msg) {
        }

        @Override
        public void error(String msg, Throwable t) {
        }

        @Override
        public DiagnosticSink diagnostics() {
            return DiagnosticSink.NOOP;
        }
    }
}
