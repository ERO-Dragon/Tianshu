package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.protocol.dialogue.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionControlPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Objects;
import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.AXParticipantRegistrar;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;

public final class AXSessionController {
    private final AXProtocolAdapter adapter;

    public AXSessionController(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public void release(TianshuEnvelope parent, DialogueDeliveryPayload delivery, DialogueReleaseReason reason) {
        if (parent == null || delivery == null || delivery.sessionId().isBlank()) {
            return;
        }
        DialogueSessionControlPayload payload = new DialogueSessionControlPayload(
                delivery.sessionId(),
                AXModule.MODULE_ID,
                AXParticipantRegistrar.PARTICIPANT_ID,
                DialogueSessionControlAction.RELEASE,
                reason == null ? DialogueReleaseReason.OWNER_COMPLETED : reason,
                0L,
                System.currentTimeMillis()
        );
        adapter.commandSessionControl(parent, payload);
    }

    public void extend(TianshuEnvelope parent, DialogueDeliveryPayload delivery, long requestedProcessingMillis) {
        if (parent == null || delivery == null || delivery.sessionId().isBlank() || requestedProcessingMillis <= 0L) {
            return;
        }
        DialogueSessionControlPayload payload = new DialogueSessionControlPayload(
                delivery.sessionId(),
                AXModule.MODULE_ID,
                AXParticipantRegistrar.PARTICIPANT_ID,
                DialogueSessionControlAction.EXTEND_PROCESSING,
                null,
                requestedProcessingMillis,
                System.currentTimeMillis()
        );
        adapter.commandSessionControl(parent, payload);
    }
}
