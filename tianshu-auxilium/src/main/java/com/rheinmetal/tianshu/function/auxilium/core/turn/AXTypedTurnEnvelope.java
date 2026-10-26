package com.rheinmetal.tianshu.function.auxilium.core.turn;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.function.auxilium.input.AXDialogueInput;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
import com.rheinmetal.tianshu.protocol.ProtocolModuleIds;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.TextPayload;

/**
 * The envelope a typed turn runs under.
 *
 * <p>A spoken turn arrives inside an envelope from the intake side, and that envelope becomes the parent of
 * everything the turn sends back: the LLM request, the speech submission, the presence activity. A typed turn
 * has no inbound envelope — the player pressed a key, not a module — so the module produces one for itself.</p>
 *
 * <p>It is built exactly like any other command envelope (source = this module, target = its own dialogue
 * capability) so downstream code cannot tell the two apart, and the turn keeps its replies correlated under
 * one id either way.</p>
 */
public final class AXTypedTurnEnvelope {

    private AXTypedTurnEnvelope() {
    }

    public static TianshuEnvelope selfEnvelope(AXDialogueInput input) {
        return EnvelopeBuilder.commandToCapability(
                ProtocolModuleIds.AX,
                AXProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.TEXT,
                new TextPayload(input.text())
        ).build();
    }
}
