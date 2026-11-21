package com.rheinmetal.tianshu.dubhe.core.turn;

import com.rheinmetal.tianshu.dubhe.DubheModule;

import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.dubhe.input.DubheDialogueInput;
import com.rheinmetal.tianshu.protocol.EnvelopeBuilder;
import com.rheinmetal.tianshu.protocol.PayloadType;
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
public final class DubheTypedTurnEnvelope {

    private DubheTypedTurnEnvelope() {
    }

    public static TianshuEnvelope selfEnvelope(DubheDialogueInput input) {
        return EnvelopeBuilder.commandToCapability(
                DubheModule.MODULE_ID,
                DubheProtocolAdapter.DIALOGUE_INPUT_CAPABILITY,
                PayloadType.TEXT,
                new TextPayload(input.text())
        ).build();
    }
}
