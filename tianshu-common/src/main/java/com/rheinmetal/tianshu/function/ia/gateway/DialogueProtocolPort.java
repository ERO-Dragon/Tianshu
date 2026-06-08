package com.rheinmetal.tianshu.function.ia.gateway;

import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueOwnerPreviewPayload;
import com.rheinmetal.tianshu.function.ia.payload.DialogueSessionEventPayload;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public interface DialogueProtocolPort {
    TianshuEnvelope publishSessionEvent(TianshuEnvelope parent, DialogueSessionEventPayload payload);

    TianshuEnvelope publishOwnerPreview(TianshuEnvelope parent, DialogueOwnerPreviewPayload payload);

    TianshuEnvelope deliverToCapability(TianshuEnvelope parent, String capabilityId, DialogueDeliveryPayload payload);
}
