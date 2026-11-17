package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.concurrent.CompletableFuture;

/**
 * Items the actor handles itself: collecting dropped stacks into its own inventory.
 *
 * <p>Requires the {@code INVENTORY} capability, because there is nowhere to put the result otherwise. A body
 * without an inventory is refused before the call reaches this port.</p>
 */
public interface EntityItemPort {

    CompletableFuture<PickupResult> pickup(ActorRef actor, int radius);

    /** Drops part of one inventory slot at the body's feet; {@code count <= 0} means the whole stack. */
    CompletableFuture<DropResult> drop(ActorRef actor, int slot, int count);

    /**
     * Hands part of one inventory slot to a player or entity within reach, by id or player name.
     *
     * <p>The stack lands at the receiver's feet and is theirs to pick up: handing something over is a social act, not a
     * transfer into their inventory, which would be a different capability with different permissions. A target that is
     * absent or out of reach is refused rather than silently dropped where the body happens to stand.</p>
     *
     * <p>The default refuses: a port that has not decided how to hand something over must say so, and callers that never
     * use it — a test double for dropping, say — should not have to implement it.</p>
     */
    default CompletableFuture<DropResult> handTo(ActorRef actor, int slot, int count, String target) {
        return CompletableFuture.failedFuture(new ToolException(ToolError.UNSUPPORTED_SIDE,
                "this item source cannot hand items to someone else"));
    }

    /**
     * Moves an inventory slot onto a body slot, or clears it with {@code inventorySlot=-1}. The main hand only
     * changes what later attacks and breaks use; the other slots are applied to the carrier, so armour counts.
     */
    CompletableFuture<EquipResult> equip(ActorRef actor, EquipRequest request);
}