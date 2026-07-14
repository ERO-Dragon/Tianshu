package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRegistrationContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleRuntimeContext;
import com.rheinmetal.tianshu.core.lifecycle.module.ModuleServiceRegistry;
import com.rheinmetal.tianshu.core.runtime.ModuleRuntimeState;
import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueTurnProcessingPolicy;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolBootstrap;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IaModuleLifecycleTest {
    private ProtocolRuntime runtime;

    @AfterEach
    void closeRuntime() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void stopRemovesReadyCapabilityAndLifecycleCleanupIsIdempotent() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleRuntimeState runtimeState = new ModuleRuntimeState();
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        IaModule ia = registeredModule(services);
        ModuleRuntimeContext context = new ModuleRuntimeContext(runtime, services, null, runtimeState);

        ia.prepare(context);
        ia.prepare(context);
        assertTrue(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));

        ia.stop();
        assertFalse(runtimeState.capabilities().isReady(IaRuntimeCapabilities.ARBITRATION));
        assertDoesNotThrow(ia::stop);
        assertDoesNotThrow(ia::destroy);
        assertDoesNotThrow(ia::destroy);
    }

    @Test
    void destroyClearsParticipantStateBeforeNewWorldInstanceRegistersAgain() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleServiceRegistry firstServices = new ModuleServiceRegistry();
        IaModule first = registeredModule(firstServices);
        first.prepare(new ModuleRuntimeContext(runtime, firstServices, null, new ModuleRuntimeState()));
        firstServices.require(DialogueParticipantRegistry.class).register(participant());
        assertFalse(firstServices.require(IaModuleService.class).snapshot().participants().isEmpty());

        first.stop();
        first.destroy();
        assertTrue(firstServices.require(IaModuleService.class).snapshot().participants().isEmpty());
        assertTrue(firstServices.require(IaModuleService.class).snapshot().sessions().isEmpty());
        runtime.unregisterModule(IaProtocolAdapter.MODULE_ID);

        ModuleServiceRegistry nextServices = new ModuleServiceRegistry();
        IaModule next = registeredModule(nextServices);
        next.prepare(new ModuleRuntimeContext(runtime, nextServices, null, new ModuleRuntimeState()));
        assertTrue(nextServices.require(IaModuleService.class).snapshot().participants().isEmpty());

        nextServices.require(DialogueParticipantRegistry.class).register(participant());
        assertFalse(nextServices.require(IaModuleService.class).snapshot().participants().isEmpty());
        next.stop();
        next.destroy();
    }

    @Test
    void protocolCloseDoesNotPreventStopAndDestroy() {
        runtime = ProtocolBootstrap.create(Runnable::run);
        ModuleServiceRegistry services = new ModuleServiceRegistry();
        IaModule ia = registeredModule(services);
        ia.prepare(new ModuleRuntimeContext(runtime, services, null, new ModuleRuntimeState()));

        runtime.close();

        assertDoesNotThrow(ia::stop);
        assertDoesNotThrow(ia::destroy);
    }

    private IaModule registeredModule(ModuleServiceRegistry services) {
        IaModule ia = new IaModule(runtime);
        ia.register(new ModuleRegistrationContext(runtime, services));
        return ia;
    }

    private DialogueParticipantDescriptor participant() {
        return new DialogueParticipantDescriptor(
                "maid",
                "module.maid",
                "Maid",
                10,
                List.of(),
                List.of(),
                List.of(),
                DialogueClaimProfile.DEFAULT_OWNER,
                "MAID.DIALOGUE_INPUT",
                DialogueTurnProcessingPolicy.DEFAULT
        );
    }
}
