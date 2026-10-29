package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public interface DialogueProtocolPort {
    boolean hasCapabilityProvider(String capabilityId);

    TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload);

    TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload);
}