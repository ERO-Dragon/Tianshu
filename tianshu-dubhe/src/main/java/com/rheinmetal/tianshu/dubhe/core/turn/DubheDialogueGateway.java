package com.rheinmetal.tianshu.dubhe.core.turn;

import com.rheinmetal.tianshu.dubhe.input.DubheDialogueInput;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.Objects;

public final class DubheDialogueGateway {
    private final DubheAccessController accessController;
    private final DubheTurnPipeline orchestrator;
    private final DubheTurnStatusPublisher statusPublisher;
    private final Runnable onUserTurnStart;

    public DubheDialogueGateway(DubheAccessController accessController, DubheTurnPipeline orchestrator) {
        this(accessController, orchestrator, null, null);
    }

    public DubheDialogueGateway(DubheAccessController accessController, DubheTurnPipeline orchestrator, DubheTurnStatusPublisher statusPublisher) {
        this(accessController, orchestrator, statusPublisher, null);
    }

    /**
     * @param onUserTurnStart runs once per accepted player delivery, before the turn starts and therefore before the
     *                        player's own line reaches the history; the event layer uses it to turn everything it was
     *                        holding into history in arrival order
     */
    public DubheDialogueGateway(DubheAccessController accessController, DubheTurnPipeline orchestrator,
                             DubheTurnStatusPublisher statusPublisher, Runnable onUserTurnStart) {
        this.accessController = Objects.requireNonNull(accessController, "accessController");
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.statusPublisher = statusPublisher;
        this.onUserTurnStart = onUserTurnStart;
    }

    public void handleDelivery(TianshuEnvelope envelope, DubheDialogueInput input, ProtocolContext context) {
        if (input == null) {
            fail(context, envelope, "INVALID_PAYLOAD", "AX dialogue input is invalid", null);
            return;
        }
        if (!accessController.canAcceptDelivery(envelope, input)) {
            fail(context, envelope, "DUBHE_DELIVERY_REJECTED", "AX dialogue delivery is not authorized", null);
            return;
        }
        try {
            // Before the orchestrator appends the player's line: the history has to read in the order the session
            // happened, so whatever the event layer was holding belongs before this turn.
            if (onUserTurnStart != null) {
                onUserTurnStart.run();
            }
            orchestrator.startTurn(envelope, input);
            complete(context, envelope);
        } catch (Exception e) {
            if (statusPublisher != null) {
                statusPublisher.failed("gateway.turn_start_failed");
            }
            fail(context, envelope, "DUBHE_TURN_START_FAILED", safeMessage(e), e);
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
