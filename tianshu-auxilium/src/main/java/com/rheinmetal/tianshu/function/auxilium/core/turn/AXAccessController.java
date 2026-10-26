package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;

/**
 * Decides whether an envelope really is a dialogue turn this module may answer.
 *
 * <p>The checks are about the envelope, not about the input: routing, packet kind, source module, target
 * capability, and whether the delivery is still worth answering. The payload type is no longer part of the
 * decision — the edge that produced the {@link AXDialogueInput} already validated it, and a typed turn has no
 * payload at all.</p>
 */
public final class AXAccessController {

    public boolean canAcceptDelivery(TianshuEnvelope envelope, AXDialogueInput input) {
        if (envelope == null || input == null) {
            return false;
        }
        if (envelope.header().payloadType() != PayloadType.DIALOGUE_DELIVERY) {
            return false;
        }
        if (envelope.header().packetType() != PacketType.COMMAND && envelope.header().packetType() != PacketType.REQUEST) {
            return false;
        }
        if (!ProtocolModuleIds.IA.equals(envelope.header().sourceId())) {
            return false;
        }
        if (envelope.header().targetMode() != TargetMode.CAPABILITY) {
            return false;
        }
        if (!AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY.equals(envelope.header().target())) {
            return false;
        }
        if (input.isExpired(System.currentTimeMillis())) {
            return false;
        }
        return !input.sessionId().isBlank();
    }
}
