package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.IaModuleService;
import com.rheinmetal.tianshu.function.ia.model.DialogueClaimProfile;
import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueTurnProcessingPolicy;

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
                List.of(),
                List.of(),
                List.of(),
                DialogueClaimProfile.DEFAULT_OWNER,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                DialogueTurnProcessingPolicy.DEFAULT
        ));
    }

    public void unregister() {
        if (!registered.compareAndSet(true, false)) {
            return;
        }
        iaService.unregisterParticipant(AXModule.MODULE_ID, PARTICIPANT_ID);
    }
}
