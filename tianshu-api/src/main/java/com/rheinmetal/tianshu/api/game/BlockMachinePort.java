package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side machine operation behind {@code minecraft:block.machine}.
 *
 * <p>Only machines the platform recognizes are accepted; anything else is refused with a reason rather than handed
 * to a generic "tick this block" call, which would be a cheat surface rather than a capability.</p>
 */
public interface BlockMachinePort {

    /**
     * @deprecated implement {@link #operate(ActorRef, MachineOpRequest)} instead; this player-only entry point
     *     stays until every adapter branch has migrated and is removed afterwards.
     */
    @Deprecated
    CompletableFuture<MachineOpResult> operate(String playerId, MachineOpRequest request);

    /** Actor-aware entry point; a body must override this, a player call falls back to the player method. */
    default CompletableFuture<MachineOpResult> operate(ActorRef actor, MachineOpRequest request) {
        if (actor.kind() == ActorRef.Kind.PLAYER) {
            return operate(actor.id(), request);
        }
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                "machine access as an agent body is not implemented on this platform"));
    }
}