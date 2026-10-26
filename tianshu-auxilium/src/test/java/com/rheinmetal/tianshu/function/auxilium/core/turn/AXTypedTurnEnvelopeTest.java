package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
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
class AXTypedTurnEnvelopeTest {

    @Test
    void aTypedTurnGetsAnEnvelopeIndistinguishableFromADeliveredOne() {
        TianshuEnvelope envelope = AXTypedTurnEnvelope.selfEnvelope(AXDialogueInput.typed("player", "钻石锄怎么用", 0L));

        assertEquals(ProtocolModuleIds.AX, envelope.header().sourceId(), "the module speaks for itself");
        assertEquals(AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY, envelope.header().target());
        assertEquals(TargetMode.CAPABILITY, envelope.header().targetMode());
        assertEquals(PacketType.COMMAND, envelope.header().packetType());
        assertEquals(PayloadType.TEXT, envelope.header().payloadType());
        assertEquals("钻石锄怎么用", assertInstanceOf(TextPayload.class, envelope.payload()).text());
    }

    @Test
    void twoTypedTurnsNeverShareAnEnvelopeId() {
        AXDialogueInput first = AXDialogueInput.typed("player", "第一个问题", 0L);
        AXDialogueInput second = AXDialogueInput.typed("player", "第二个问题", 0L);

        assertNotEquals(
                AXTypedTurnEnvelope.selfEnvelope(first).envelopeId(),
                AXTypedTurnEnvelope.selfEnvelope(second).envelopeId(),
                "replies are correlated by envelope id, so it has to be unique per turn"
        );
    }

    @Test
    void theEnvelopeCarriesTheTextExactlyAsTyped() {
        TianshuEnvelope envelope = AXTypedTurnEnvelope.selfEnvelope(
                AXDialogueInput.typed("player", "  空格 也要保留  ", 0L));

        TextPayload payload = assertInstanceOf(TextPayload.class, envelope.payload());
        assertTrue(payload.text().contains("空格"));
    }
}
