package com.rheinmetal.tianshu.ia.gateway;

import com.rheinmetal.tianshu.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public interface DialogueProtocolPort {
    boolean hasCapabilityProvider(String capabilityId);

    TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload);

    /**
     * Broadcasts a session event that answers no request.
     *
     * <p>Unregistering a participant happens outside any envelope, and the resulting events are standalone
     * product broadcasts rather than replies — the same shape the partner marker already uses.</p>
     */
    TianshuEnvelope publishSessionEvent(DialogueSessionEventPayload payload);

    TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload);
}