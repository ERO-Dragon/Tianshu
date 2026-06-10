package com.rheinmetal.tianshu.function.ir.routing;

import com.rheinmetal.tianshu.function.ir.IrMatchBatch;
import com.rheinmetal.tianshu.function.ir.IrVoiceMatch;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IrRoutingPolicyTest {
    @Test
    void routesMatchedVoiceTriggerToDialogueArbitration() {
        IrRoutingPolicy policy = new IrRoutingPolicy();
        IrInputText input = new IrInputText("打开设置", "打开设置", 1, 0L, "asr", 100L);
        IrMatchBatch batch = new IrMatchBatch(input, List.of(new IrVoiceMatch("module.settings", List.of("设置"), List.of(), 1.0D)));

        IrRoutingDecision decision = policy.decide(input, batch);

        assertEquals(IrRouteKind.DIALOGUE_ARBITRATION, decision.kind());
        assertEquals("OPEN_DIALOGUE_INPUT", decision.reason());
    }

    @Test
    void routesNonCommandInputToDialogueArbitration() {
        IrRoutingPolicy policy = new IrRoutingPolicy();
        IrInputText input = new IrInputText("你是谁", "你是谁", 1, 0L, "asr", 100L);
        IrMatchBatch batch = new IrMatchBatch(input, List.of());

        IrRoutingDecision decision = policy.decide(input, batch);

        assertEquals(IrRouteKind.DIALOGUE_ARBITRATION, decision.kind());
        assertEquals("OPEN_DIALOGUE_INPUT", decision.reason());
    }

    @Test
    void rejectsBlankInputBeforeDialogueArbitration() {
        IrRoutingPolicy policy = new IrRoutingPolicy();
        IrInputText input = new IrInputText("", "", 1, 0L, "asr", 100L);

        IrRoutingDecision decision = policy.decide(input, null);

        assertEquals(IrRouteKind.NO_MATCH, decision.kind());
        assertEquals("EMPTY_INPUT", decision.reason());
    }
}
