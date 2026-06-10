package com.rheinmetal.tianshu.function.ir.routing;

import com.rheinmetal.tianshu.function.ir.IrMatchBatch;

public record IrRoutingDecision(IrRouteKind kind, IrMatchBatch matchBatch, String reason) {
    public IrRoutingDecision {
        kind = kind == null ? IrRouteKind.NO_MATCH : kind;
        reason = reason == null ? "" : reason.trim();
    }

    public static IrRoutingDecision dialogueArbitration(IrMatchBatch matchBatch) {
        return new IrRoutingDecision(IrRouteKind.DIALOGUE_ARBITRATION, matchBatch, "OPEN_DIALOGUE_INPUT");
    }

    public static IrRoutingDecision noMatch(String reason) {
        return new IrRoutingDecision(IrRouteKind.NO_MATCH, null, reason);
    }
}
