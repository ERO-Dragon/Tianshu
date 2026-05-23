package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.function.ia.model.DialogueInterruptPolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueLeasePolicy;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AXParticipantRegistrar {
    public static final String PARTICIPANT_ID = "tianshu.AX";
    public static final String DISPLAY_NAME = "辅星";
    private final IaModuleService iaService;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    public AXParticipantRegistrar(IaModuleService iaService) {
        this.iaService = Objects.requireNonNull(iaService, "iaService");
    }

    public void register() {
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        iaService.registerParticipant(new DialogueParticipantDescriptor(
                PARTICIPANT_ID,
                AXModule.MODULE_ID,
                DISPLAY_NAME,
                0,
                List.of("AX", "chat", "help"),
                List.of(),
                List.of(),
                AXProtocolAdapter.DIALOGUE_DELIVERY_CAPABILITY,
                DialogueInterruptPolicy.ALLOW_AFTER_LEASE,
                DialogueLeasePolicy.DEFAULT
        ));
    }

    public void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        iaService.unregisterParticipant(AXModule.MODULE_ID, PARTICIPANT_ID);
    }
}
