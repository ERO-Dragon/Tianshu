package com.rheinmetal.tianshu.function.auxilium;

import com.rheinmetal.tianshu.function.ia.IaProtocolAdapter;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

public final class AXAccessController {
    public boolean canAcceptDelivery(TianshuEnvelope envelope, DialogueDeliveryPayload payload) {
        if (envelope == null || payload == null) {
            return false;
        }
        if (envelope.header().payloadType() != PayloadType.DIALOGUE_DELIVERY) {
            return false;
        }
        if (envelope.header().packetType() != PacketType.COMMAND && envelope.header().packetType() != PacketType.REQUEST) {
            return false;
        }
        if (!IaProtocolAdapter.SOURCE_ID.equals(envelope.header().sourceId())) {
            return false;
        }
        if (envelope.header().targetMode() != TargetMode.CAPABILITY) {
            return false;
        }
        if (!AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY.equals(envelope.header().target())) {
            return false;
        }
        if (payload.expireAtMillis() > 0L && payload.expireAtMillis() < System.currentTimeMillis()) {
            return false;
        }
        return !payload.sessionId().isBlank() && !payload.requestId().isBlank();
    }
}
