package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.game.InventorySnapshot;

import java.util.concurrent.CompletableFuture;

/**
 * What an actor can see right now: nearby blocks and entities, and its own inventory.
 *
 * <p>Read-only and side-less: it never changes the world, so it needs no capability and no switch. An adapter
 * clamps the radius and the result count; a body that does not exist is {@code UNAVAILABLE}, not an empty result.</p>
 */
public interface EntityQueryPort {

    CompletableFuture<ScanResult> scan(ActorRef actor, ScanRequest request);

    CompletableFuture<InventorySnapshot> inventory(ActorRef actor);
}