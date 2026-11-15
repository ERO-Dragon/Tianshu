package com.rheinmetal.tianshu.client.tool;

import com.rheinmetal.tianshu.api.agent.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolCaller;

/**
 * Chooses who a call runs as. A body may name itself explicitly; a player may drive a body by naming it, in which
 * case the player's permission still governs the call.
 */
final class ActorSelection {

    private ActorSelection() {
    }

    static ActorRef of(ToolCaller caller, String bodyId) {
        if (caller.kind() == ToolCaller.Kind.BODY) {
            return ActorRef.of(caller);
        }
        if (bodyId != null && !bodyId.isBlank()) {
            return ActorRef.body(bodyId.trim(), caller.operator());
        }
        return ActorRef.of(caller);
    }
}