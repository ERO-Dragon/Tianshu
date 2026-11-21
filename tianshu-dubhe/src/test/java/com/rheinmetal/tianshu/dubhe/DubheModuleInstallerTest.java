package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.TianshuModuleHost;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheChatOutputSink;
import com.rheinmetal.tianshu.dubhe.dialogue.output.DubheOutputSettings;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DubheModuleInstallerTest {
    @TempDir
    Path tempDir;

    @Test
    void disabledAssistantIsNotInstalledIntoTheModuleLifecycle() {
        FakeGameEnvironment environment = new FakeGameEnvironment();
        TianshuModuleHost moduleHost = new TianshuModuleHost(environment);
        DubheAssistantSettings disabledSettings = new DubheAssistantSettings() {
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
        FakeGameEnvironment environment = new FakeGameEnvironment();
        TianshuModuleHost moduleHost = new TianshuModuleHost(environment);

        installer(environment, DubheAssistantSettings.DEFAULT).install(moduleHost, new ModuleServiceRegistry());

        assertEquals(1, moduleHost.managedModules().size());
        assertEquals(DubheModule.MODULE_ID, moduleHost.managedModules().get(0).moduleId());
    }

    private DubheModuleInstaller installer(FakeGameEnvironment environment, DubheAssistantSettings settings) {
        return new DubheModuleInstaller(
                environment,
                new TestLlmSupport.FakeConfig(tempDir.resolve("module")),
                new ProtocolRuntime(Runnable::run),
                null,
                null,
                settings,
                DubheOutputSettings.DEFAULT,
                DubheChatOutputSink.NOOP
        );
    }
}
