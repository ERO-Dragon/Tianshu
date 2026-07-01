package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Objects;

public final class AXDialogueGateway {
    private final AXAccessController accessController;
    private final AXTurnPipeline orchestrator;
    private final AXTurnStatusPublisher statusPublisher;

    public AXDialogueGateway(AXAccessController accessController, AXTurnPipeline orchestrator) {
        this(accessController, orchestrator, null);
    }

    public AXDialogueGateway(AXAccessController accessController, AXTurnPipeline orchestrator, AXTurnStatusPublisher statusPublisher) {
        this.accessController = Objects.requireNonNull(accessController, "accessController");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.statusPublisher = statusPublisher;
    }

    public void handleDelivery(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof DialogueDeliveryPayload payload)) {
            fail(context, envelope, "INVALID_PAYLOAD", "AX dialogue delivery payload is invalid", null);
            return;
        }
        if (!accessController.canAcceptDelivery(envelope, payload)) {
            fail(context, envelope, "AX_DELIVERY_REJECTED", "AX dialogue delivery is not authorized", null);
            return;
        }
        try {
            orchestrator.startTurn(envelope, payload);
            complete(context, envelope);
        } catch (Exception e) {
            if (statusPublisher != null) {
                statusPublisher.failed("gateway.turn_start_failed");
            }
            fail(context, envelope, "AX_TURN_START_FAILED", safeMessage(e), e);
        }
    }

    private void complete(ProtocolContext context, TianshuEnvelope envelope) {
        if (context != null && envelope != null) {
            context.complete(envelope.envelopeId());
        }
    }

    private void fail(ProtocolContext context, TianshuEnvelope envelope, String code, String message, Throwable throwable) {
        if (context != null && envelope != null) {
            context.fail(envelope.envelopeId(), code, message, throwable);
        }
    }

    private String safeMessage(Exception e) {
        String message = e == null ? null : e.getMessage();
        return message == null || message.isBlank() ? "AX turn start failed" : message;
    }
}
