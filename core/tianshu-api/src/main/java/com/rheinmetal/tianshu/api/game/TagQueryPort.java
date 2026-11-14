package com.rheinmetal.tianshu.api.game;

import java.util.concurrent.CompletableFuture;

/**
 * The client-side tag source behind {@code tianshu:tag.query}.
 *
 * <p>Item and block tags are synced to the client, so this is a local capability; implementations must complete
 * the future on the client thread and must not block the caller.</p>
 */
public interface TagQueryPort {

    CompletableFuture<TagQueryResult> query(TagQuery query);
}
