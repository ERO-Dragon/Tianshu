package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side block interaction behind {@code tianshu:block.interact}.
 *
 * <p>Interaction is a world change, so this exists only on the server; the client ships a declaration that routes
 * through the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface BlockInteractPort {

    CompletableFuture<BlockInteractResult> interact(String playerId, BlockInteractRequest request);
}
