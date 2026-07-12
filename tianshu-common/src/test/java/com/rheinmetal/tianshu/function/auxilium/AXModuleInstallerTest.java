package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AXModuleInstallerTest {
    @TempDir
    Path tempDir;

    @Test
    void disabledAssistantIsNotInstalledIntoTheModuleLifecycle() {
        TestLlmSupport.FakeGameEnvironment environment = new TestLlmSupport.FakeGameEnvironment();
        TianshuModuleHost moduleHost = new TianshuModuleHost(environment);
        AXAssistantSettings disabledSettings = new AXAssistantSettings() {
            @Override
            public String wakeWord() {
                return DEFAULT_WAKE_WORD;
            }

            @Override
            public boolean assistantEnabled() {
                return false;
            }
        };

        installer(environment, disabledSettings).install(moduleHost, new ModuleServiceRegistry());

        assertEquals(0, moduleHost.managedModules().size());
    }

    @Test
    void enabledAssistantInstallsExactlyOneAxModule() {
        TestLlmSupport.FakeGameEnvironment environment = new TestLlmSupport.FakeGameEnvironment();
        TianshuModuleHost moduleHost = new TianshuModuleHost(environment);

        installer(environment, AXAssistantSettings.DEFAULT).install(moduleHost, new ModuleServiceRegistry());

        assertEquals(1, moduleHost.managedModules().size());
        assertEquals(AXModule.MODULE_ID, moduleHost.managedModules().get(0).moduleId());
    }

    private AXModuleInstaller installer(TestLlmSupport.FakeGameEnvironment environment, AXAssistantSettings settings) {
        return new AXModuleInstaller(
                environment,
                new TestLlmSupport.FakeConfig(tempDir.resolve("module")),
                new ProtocolRuntime(Runnable::run),
                null,
                null,
                settings,
                AXOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );
    }
}
