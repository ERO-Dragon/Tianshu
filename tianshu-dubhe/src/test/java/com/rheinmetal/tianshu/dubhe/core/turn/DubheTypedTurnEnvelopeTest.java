package com.rheinmetal.tianshu.dubhe.core.turn;

import com.rheinmetal.tianshu.dubhe.DubheModule;

import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.input.DubheDialogueInput;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.TargetMode;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A typed turn runs under an envelope the module made for itself.
 *
 * <p>What matters is that the turn cannot tell the difference: same kind of envelope, same packet type, the
 * module as its own source, and the text carried as the payload. Downstream code keys its replies off this
 * envelope's id, so two typed turns must never share one.</p>
 */
class DubheTypedTurnEnvelopeTest {

    @Test
    void aTypedTurnGetsAnEnvelopeIndistinguishableFromADeliveredOne() {
        TianshuEnvelope envelope = DubheTypedTurnEnvelope.selfEnvelope(DubheDialogueInput.typed("player", "钻石锄怎么用", 0L));

        assertEquals(DubheModule.MODULE_ID, envelope.header().sourceId(), "the module speaks for itself");
        assertEquals(DubheProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, envelope.header().target());
        assertEquals(TargetMode.CAPABILITY, envelope.header().targetMode());
        assertEquals(PacketType.COMMAND, envelope.header().packetType());
        assertEquals(PayloadType.TEXT, envelope.header().payloadType());
        assertEquals("钻石锄怎么用", assertInstanceOf(TextPayload.class, envelope.payload()).text());
    }

    @Test
    void twoTypedTurnsNeverShareAnEnvelopeId() {
        DubheDialogueInput first = DubheDialogueInput.typed("player", "第一个问题", 0L);
        DubheDialogueInput second = DubheDialogueInput.typed("player", "第二个问题", 0L);

        assertNotEquals(
                DubheTypedTurnEnvelope.selfEnvelope(first).envelopeId(),
                DubheTypedTurnEnvelope.selfEnvelope(second).envelopeId(),
                "replies are correlated by envelope id, so it has to be unique per turn"
        );
    }

    @Test
    void theEnvelopeCarriesTheTextExactlyAsTyped() {
        TianshuEnvelope envelope = DubheTypedTurnEnvelope.selfEnvelope(
                DubheDialogueInput.typed("player", "  空格 也要保留  ", 0L));

        TextPayload payload = assertInstanceOf(TextPayload.class, envelope.payload());
        assertTrue(payload.text().contains("空格"));
    }
}
