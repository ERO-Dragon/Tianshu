package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.auxilium.protocol.AxCapabilities;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.auxilium.core.delegate.AXDelegateService;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventService;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXModuleGameEventWiringTest {
    @TempDir
    Path tempDir;

    @Test
    void moduleServicesArePublishedWhileTheSessionRunsAndWithdrawnWhenItStops() {
        FakeGameEnvironment env = new FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        AXModule module = new AXModule(
                env,
                config,
                runtime,
                null,
                null,
                () -> "天枢",
                AXRuntimePolicy.defaults(),
                AuxiliumOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );

        module.register(new ModuleRegistrationContext(runtime, services));
        assertFalse(services.find(AXGameEventService.class).isPresent());
        assertEquals(1, runtime.capabilityProviderCount(AxCapabilities.AX_EVENT_INPUT),
                "the event entry exists as soon as the module is registered, before the layer runs");

        module.prepare(new ModuleRuntimeContext(
                runtime,
                services,
                new TriggerRegistry(),
                new ModuleRuntimeState()
        ));

        AXGameEventService service = services.find(AXGameEventService.class).orElseThrow();
        assertFalse(service.closed());
        assertTrue(services.find(AXDelegateService.class).isPresent());

        // Any module that only holds the registry handle submits here; nothing marks a source as the
        // privileged one, so a third-party kind takes the same call as a built-in one.
        service.submit(AXEventDescriptor.of("plugin:harvest", "plugin.farming", AXEventPayload.of(),
                AXEventText.of("tianshu.ax.event.line.plugin.harvest")));
        service.submit(AXEventDescriptor.of("tianshu:death", "module.probe", AXEventPayload.of(),
                AXEventText.of("tianshu.ax.event.line.tianshu.death")));
        assertFalse(service.closed());

        module.stop();

        assertTrue(service.closed());
        assertFalse(services.find(AXGameEventService.class).isPresent());
        assertFalse(services.find(AXDelegateService.class).isPresent());
    }
}
