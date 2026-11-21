package com.rheinmetal.tianshu.dubhe;

import com.rheinmetal.tianshu.dubhe.protocol.DubheCapabilities;

import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.dubhe.core.delegate.DubheDelegateService;
import com.rheinmetal.tianshu.dubhe.core.output.DubheChatOutputSink;
import com.rheinmetal.tianshu.dubhe.core.output.DubheOutputSettings;
import com.rheinmetal.tianshu.dubhe.event.DubheEventDescriptor;
import com.rheinmetal.tianshu.dubhe.event.DubheEventPayload;
import com.rheinmetal.tianshu.dubhe.event.DubheEventText;
import com.rheinmetal.tianshu.dubhe.producer.DubheGameEventService;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DubheModuleGameEventWiringTest {
    @TempDir
    Path tempDir;

    @Test
    void moduleServicesArePublishedWhileTheSessionRunsAndWithdrawnWhenItStops() {
        FakeGameEnvironment env = new FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        DubheModule module = new DubheModule(
                env,
                config,
                runtime,
                null,
                null,
                () -> "天枢",
                DubheRuntimePolicy.defaults(),
                DubheOutputSettings.DEFAULT,
                DubheChatOutputSink.NOOP
        );

        module.register(new ModuleRegistrationContext(runtime, services));
        assertFalse(services.find(DubheGameEventService.class).isPresent());
        assertEquals(1, runtime.capabilityProviderCount(DubheCapabilities.DUBHE_EVENT_INPUT),
                "the event entry exists as soon as the module is registered, before the layer runs");

        module.prepare(new ModuleRuntimeContext(
                runtime,
                services,
                new TriggerRegistry(),
                new ModuleRuntimeState()
        ));

        DubheGameEventService service = services.find(DubheGameEventService.class).orElseThrow();
        assertFalse(service.closed());
        assertTrue(services.find(DubheDelegateService.class).isPresent());

        // Any module that only holds the registry handle submits here; nothing marks a source as the
        // privileged one, so a third-party kind takes the same call as a built-in one.
        service.submit(DubheEventDescriptor.of("plugin:harvest", "plugin.farming", DubheEventPayload.of(),
                DubheEventText.of("tianshu.dubhe.event.line.plugin.harvest")));
        service.submit(DubheEventDescriptor.of("tianshu:death", "module.probe", DubheEventPayload.of(),
                DubheEventText.of("tianshu.dubhe.event.line.tianshu.death")));
        assertFalse(service.closed());

        module.stop();

        assertTrue(service.closed());
        assertFalse(services.find(DubheGameEventService.class).isPresent());
        assertFalse(services.find(DubheDelegateService.class).isPresent());
    }
}
