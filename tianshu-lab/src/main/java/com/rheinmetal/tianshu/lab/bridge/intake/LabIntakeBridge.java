package com.rheinmetal.tianshu.lab.bridge.intake;

import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

import java.util.Optional;

/** Intake-owned payload access, called only after the host confirms Intake is installed. */
public final class LabIntakeBridge {

    public record Delivery(String sessionId, String repairedText, String normalizedText) {
    }

    private LabIntakeBridge() {
    }

    public static Optional<Delivery> delivery(TianshuEnvelope envelope) {
        if (envelope == null || !(envelope.payload() instanceof DialogueDeliveryPayload payload)) {
            return Optional.empty();
        }
        return Optional.of(new Delivery(payload.sessionId(), payload.repairedText(), payload.normalizedText()));
    }
}
