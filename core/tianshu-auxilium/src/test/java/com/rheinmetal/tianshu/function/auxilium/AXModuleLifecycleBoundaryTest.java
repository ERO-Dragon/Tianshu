package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.auxilium.core.output.AXChatOutputSink;
import com.rheinmetal.tianshu.function.auxilium.core.output.AuxiliumOutputSettings;
import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.contract.DialogueParticipantRegisterPayload;
import com.rheinmetal.tianshu.testkit.TestLlmSupport;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.protocol.trigger.TriggerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AXModuleLifecycleBoundaryTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearPorts() {
        TianshuRuntimePorts.clear();
    }

    @Test
    void participantRegistrationHappensDuringPrepareNotRegister() {
        FakeGameEnvironment env = new FakeGameEnvironment();
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
                AuxiliumOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );

        // The dialogue domain is an optional partner: the channel is chosen when the module registers, which in
        // a real installation happens long after the platform registered what is installed.
        TianshuRuntimePorts.register(InstalledMods.class, "tianshu_intake"::equals);

        module.register(new ModuleRegistrationContext(runtime, services));

        await(() -> registered.get() != null, 150L);
        assertNull(registered.get());

        module.prepare(new ModuleRuntimeContext(
                runtime,
                services,
                new TriggerRegistry(),
                new ModuleRuntimeState()
        ));

        await(() -> registered.get() != null, 2_000L);
        assertEquals(AXParticipantRegistrar.PARTICIPANT_ID, registered.get().descriptor().displayName(),
                "the wake word stays a wake word; the shown name is UI text, and this fixture wires no language table");
    }

    /**
     * The other half of the optional-partner contract: with no dialogue domain installed the module must stay
     * silent. Nothing throws, nothing is announced, and the typed input path keeps working — that is what makes
     * the partner genuinely optional rather than merely guarded.
     */
    @Test
    void nothingIsAnnouncedWhenTheDialogueDomainIsAbsent() {
        FakeGameEnvironment env = new FakeGameEnvironment();
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
                AuxiliumOutputSettings.DEFAULT,
                AXChatOutputSink.NOOP
        );

        module.register(new ModuleRegistrationContext(runtime, services));
        module.prepare(new ModuleRuntimeContext(
                runtime,
                services,
                new TriggerRegistry(),
                new ModuleRuntimeState()
        ));

        await(() -> registered.get() != null, 300L);
        assertNull(registered.get(), "without the dialogue domain nothing is announced");
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
