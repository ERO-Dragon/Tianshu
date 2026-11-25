package com.rheinmetal.tianshu.api.game;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The recipe source the {@code minecraft:recipe.query} tool asks. From 1.21.2 the client no longer receives the full
 * recipe manager, so this port is implemented server-side on every version; the tool itself is a normal
 * {@code SERVER_READ} tool.
 *
 * <p>This is a port, not an implementation: the recipe API differs per Minecraft line, so each generation implements
 * it once and the platform-neutral client module owns the tool, its policy and its JSON shape.</p>
 *
 * <p>Implementations must complete the future on the server thread, and must not block the caller.</p>
 */
public interface RecipeQueryPort {

    CompletableFuture<List<RecipeInfo>> query(RecipeQuery query);
}