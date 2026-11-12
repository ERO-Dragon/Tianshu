package com.rheinmetal.tianshu.api.game;

/**
 * Craft one recipe once, by recipe id.
 *
 * <p>Requiring the id keeps the assistant honest: it must name the recipe it means, and the server never picks one
 * on its own. Discovering recipes is {@code recipe.query}'s job.</p>
 */
public record CraftRequest(String recipeId) {
}
