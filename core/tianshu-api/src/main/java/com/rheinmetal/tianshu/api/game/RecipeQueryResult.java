package com.rheinmetal.tianshu.api.game;

import java.util.List;

/** The tool result of {@code tianshu:recipe.query}; a wrapper so the result type stays a plain class. */
public record RecipeQueryResult(List<RecipeInfo> recipes) {
}