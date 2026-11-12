package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The server-side container transfer behind {@code tianshu:container.transfer}.
 *
 * <p>Moving items changes two inventories, so this exists only on the server; the client ships a declaration that
 * routes through the tool channel. Implementations must complete the future on the server thread.</p>
 */
public interface ContainerTransferPort {

    CompletableFuture<ContainerTransferResult> transfer(String playerId, ContainerTransferRequest request);
}
