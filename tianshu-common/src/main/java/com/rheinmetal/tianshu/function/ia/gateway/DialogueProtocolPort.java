package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public interface DialogueProtocolPort {
    boolean hasCapabilityProvider(String capabilityId);

    TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload);

    TianshuEnvelope publishOwnerPreview(TianshuEnvelope parent, DialogueOwnerPreviewPayload payload);

    TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload);
}
