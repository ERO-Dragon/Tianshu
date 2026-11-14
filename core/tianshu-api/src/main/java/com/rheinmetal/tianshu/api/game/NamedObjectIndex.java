package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The kernel's handle on a module's named-object index.
 *
 * <p>The kernel starts it with the client, refreshes it when the game reloads resources and closes it on
 * shutdown — three lifecycle calls, no knowledge of what is indexed or how. The module owns the index and
 * its storage; a module that is not installed simply never registers one.</p>
 */
public interface NamedObjectIndex extends AutoCloseable {

    CompletableFuture<Boolean> initializeAsync(String reason);

    CompletableFuture<Boolean> reloadAsync(String reason, Runnable beforeRebuild);

    @Override
    void close();
}
