package com.rheinmetal.tianshu.api.game;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The client-side recipe source the {@code tianshu:recipe.query} tool asks.
 *
 * <p>This is a port, not an implementation: the recipe API differs per Minecraft line, so each platform layer
 * implements it once and the platform-neutral client module owns the tool, its policy and its JSON shape. That
 * keeps the tool's behaviour identical on every version while the version-specific lookup stays where it belongs.</p>
 *
 * <p>Implementations must complete the future on the client thread, and must not block the caller.</p>
 */
public interface RecipeQueryPort {

    CompletableFuture<List<RecipeInfo>> query(RecipeQuery query);
}