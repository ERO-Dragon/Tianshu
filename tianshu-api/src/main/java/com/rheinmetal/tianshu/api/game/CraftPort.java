package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side crafting behind {@code tianshu:craft.recipe}.
 *
 * <p>Crafting consumes items, so this exists only on the server; the client ships a declaration that routes through
 * the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface CraftPort {

    CompletableFuture<CraftResult> craft(String playerId, CraftRequest request);
}
