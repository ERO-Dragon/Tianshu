package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side loot source behind {@code tianshu:loot.query}.
 *
 * <p>Loot tables are server state and are never synced to the client, so this port exists only on the server; the
 * client side of the tool is a declaration that routes through the tool channel. Implementations must complete the
 * future on the server thread.</p>
 */
public interface LootQueryPort {

    CompletableFuture<LootQueryResult> query(LootQuery query);
}
