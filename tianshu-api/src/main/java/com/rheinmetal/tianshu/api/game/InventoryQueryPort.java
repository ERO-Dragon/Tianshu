package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The client-side inventory source behind {@code tianshu:inventory.inspect}.
 *
 * <p>Only the local player's own inventory is readable here; other players' inventories are authoritative server
 * state and belong to a server-side tool. Implementations must complete the future on the client thread.</p>
 */
public interface InventoryQueryPort {

    CompletableFuture<InventorySnapshot> read();
}
