package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * Reads one stack in an actor's inventory.
 *
 * <p>Atomic, and the read that makes the write tools decidable: {@code body.equip} and {@code item.use} act on whatever
 * the hand holds, so a caller that cannot see durability or enchantments is choosing without information.</p>
 */
public interface ItemInspectPort {

    CompletableFuture<ItemInspectResult> inspect(ActorRef actor, ItemInspectRequest request);
}