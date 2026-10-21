package com.rheinmetal.tianshu.function.llm;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.llm.service.LLMService;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;
import com.rheinmetal.tianshu.function.llm.runtime.LlmRuntimeState;
import com.rheinmetal.tianshu.core.runtime.RuntimeCapabilityState;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmModuleLifecycleBoundaryTest {
    @TempDir
    Path tempDir;

    @Test
    void registerPublishesStableControlServicesButNotRuntimeBeforeLoad() {
        IGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();

        LlmModule module = new LlmModule(env, config, runtime);
        module.register(new ModuleRegistrationContext(runtime, services));

        assertTrue(services.find(LlmModuleService.class).isPresent());
        assertTrue(services.find(LlmModelService.class).isPresent());
        assertTrue(services.find(LLMService.class).isEmpty());
    }

    @Test
    void startDefersAutomaticLoadUntilAfterWorldSessionIsReady() throws Exception {
        IGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        // The cloud lane only needs an endpoint: no local chat GGUF exists for a cloud user, and its
        // absence must not stop the module from starting.
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir)
                .llmAutoLoadDelayMillis(20L)
                .remoteApiKey("test-key");
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        LlmModule module = new LlmModule(env, config, runtime);
        module.register(new ModuleRegistrationContext(runtime, services));
        module.prepare(new ModuleRuntimeContext(runtime, services, new VoiceResourceManager(env, config), new ModuleRuntimeState()));

        module.start(null);

        LlmModuleService service = services.require(LlmModuleService.class);
        assertEquals(LlmRuntimeState.STOPPED, service.snapshot().state());
        assertEquals(LlmRuntimeState.RUNNING, awaitState(service, LlmRuntimeState.RUNNING, 5_000L));
    }

    @Test
    void startWithoutCloudCredentialFailsTheLaneInsteadOfFallingBackToLocalModel() {
        IGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir).llmAutoLoadDelayMillis(0L);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        LlmModule module = new LlmModule(env, config, runtime);
        module.register(new ModuleRegistrationContext(runtime, services));
        module.prepare(new ModuleRuntimeContext(runtime, services, new VoiceResourceManager(env, config), new ModuleRuntimeState()));

        LlmModuleService service = services.require(LlmModuleService.class);
        service.load();

        // The failure carries a resource key so the settings page can tell the player what to fill in,
        // and no local chat model is loaded as a fallback.
        assertEquals(LlmRuntimeState.FAILED, service.snapshot().state());
        assertEquals("tianshu.presence.module.llm.missing_api_key", service.snapshot().failureMessage());
        assertTrue(services.find(LLMService.class).isEmpty());
    }

    @Test
    void storageCapabilitiesRemainReadyBeforeGenerationLoads() {
        IGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        ModuleRuntimeState state = new ModuleRuntimeState();
        LlmModule module = new LlmModule(env, config, runtime);
        module.register(new ModuleRegistrationContext(runtime, services));
        module.prepare(new ModuleRuntimeContext(runtime, services, new VoiceResourceManager(env, config), state));

        assertEquals(RuntimeCapabilityState.FAILED, state.capabilities().status(LlmRuntimeCapabilities.LLM_REQUEST).state());
        assertEquals(RuntimeCapabilityState.READY, state.capabilities().status(LlmRuntimeCapabilities.LLM_CACHE_MANAGE).state());
        assertEquals(RuntimeCapabilityState.READY, state.capabilities().status(LlmRuntimeCapabilities.LLM_PRIMITIVE_QUERY).state());
    }

    /** Polls until the deferred auto-load settles on the expected state or fails. */
    private static LlmRuntimeState awaitState(LlmModuleService service, LlmRuntimeState expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        LlmRuntimeState current = service.snapshot().state();
        while (current != expected && current != LlmRuntimeState.FAILED && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
            current = service.snapshot().state();
        }
        return current;
    }
}
