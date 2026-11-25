package com.rheinmetal.tianshu.api.game;

import com.rheinmetal.tianshu.api.entity.ActorRef;

import java.util.concurrent.CompletableFuture;

/**
 * What this actor could craft right now, out of what it is carrying.
 *
 * <p>The reverse of {@code recipe.query}: that one answers "how do I make X" when the caller already knows X, and this
 * one answers "what can I make" when the caller does not. An agent that has to guess item ids asks the wrong question
 * forever; this is the tool that lets it stop guessing.</p>
 *
 * <p>Atomic: it reports possibilities, it does not craft (that is {@code craft.recipe}) and it does not fetch missing
 * ingredients (that is a workflow). Results are bounded by the caller's limit, because a full inventory matches
 * hundreds of recipes and an answer nobody can read is not an answer.</p>
 */
public interface RecipeAvailabilityPort {

    CompletableFuture<CraftableRecipeList> available(ActorRef actor, int limit);
}