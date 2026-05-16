package com.rheinmetal.tianshu.function.assistant;

import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.function.ia.model.DialogueInterruptPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueLeasePolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AssistantParticipantRegistrar {
    public static final String PARTICIPANT_ID = "tianshu.assistant";
    public static final String DISPLAY_NAME = "天枢助手";
    private final IaModuleService iaService;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public AssistantParticipantRegistrar(IaModuleService iaService) {
        this.iaService = Objects.requireNonNull(iaService, "iaService");
    }

    public void register() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        iaService.registerParticipant(new DialogueParticipantDescriptor(
                PARTICIPANT_ID,
                AssistantModule.MODULE_ID,
                DISPLAY_NAME,
                0,
                List.of("assistant", "chat", "help"),
                List.of(),
                List.of(),
                AssistantProtocolAdapter.DIALOGUE_DELIVERY_CAPABILITY,
                DialogueInterruptPolicy.ALLOW_AFTER_LEASE,
                DialogueLeasePolicy.DEFAULT
        ));
    }

    public void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        iaService.unregisterParticipant(AssistantModule.MODULE_ID, PARTICIPANT_ID);
    }
}
