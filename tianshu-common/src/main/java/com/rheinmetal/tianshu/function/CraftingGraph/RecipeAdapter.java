package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.RecipeData;

public interface RecipeAdapter {
    boolean supports(RecipeData recipe);
    UniversalRecipeViewModel adapt(RecipeData recipe);
}
