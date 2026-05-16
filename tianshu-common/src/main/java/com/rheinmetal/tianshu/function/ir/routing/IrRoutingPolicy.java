package com.rheinmetal.tianshu.function.ir.routing;

import com.rheinmetal.tianshu.function.ir.IrMatchBatch;
import com.rheinmetal.tianshu.function.ir.input.IrInputText;

public final class IrRoutingPolicy {
    public IrRoutingDecision decide(IrInputText input, IrMatchBatch matchBatch) {
        if (input == null || input.blank()) {
            return IrRoutingDecision.noMatch("EMPTY_INPUT");
        }
        if (matchBatch != null && matchBatch.matched()) {
            return IrRoutingDecision.directVoiceTrigger(matchBatch);
        }
        return IrRoutingDecision.dialogueArbitration(matchBatch);
    }
}
