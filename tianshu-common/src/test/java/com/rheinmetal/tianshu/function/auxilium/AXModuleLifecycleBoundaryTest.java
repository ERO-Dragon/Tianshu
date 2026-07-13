package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXOutputSettings;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AXModuleLifecycleBoundaryTest {
    @TempDir
    Path tempDir;

    @Test
    void participantRegistrationHappensDuringPrepareNotRegister() {
        TestLlmSupport.FakeGameEnvironment env = new TestLlmSupport.FakeGameEnvironment();
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        ProtocolRuntime runtime = new ProtocolRuntime(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        AtomicReference<DialogueParticipantRegisterPayload> registered = new AtomicReference<>();
        IaProtocolAdapter iaAdapter = new IaProtocolAdapter(runtime);
        iaAdapter.registerParticipantCapability((envelope, context) -> {
            registered.set((DialogueParticipantRegisterPayload) envelope.payload());
            context.complete(envelope.envelopeId());
        });
        AXModule module = new AXModule(
                env,
                config,
                runtime,
                null,
                null,
                () -> "天枢",
                AXRuntimePolicy.defaults(),
                AXOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );

        module.register(new ModuleRegistrationContext(runtime, services));

        await(() -> registered.get() != null, 150L);
        assertNull(registered.get());

        module.prepare(new ModuleRuntimeContext(
                runtime,
                services,
                new VoiceResourceManager(env, config),
                new ModuleRuntimeState()
        ));

        await(() -> registered.get() != null, 2_000L);
        assertEquals("天枢", registered.get().descriptor().displayName());
    }

    private static void await(java.util.function.BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
