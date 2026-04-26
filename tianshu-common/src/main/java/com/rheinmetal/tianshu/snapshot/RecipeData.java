package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class RecipeData {

    public final String recipeId;
    public final String recipeType;
    public final IngredientData result;
    public final List<IngredientData> ingredients;

    public RecipeData(String recipeId, String recipeType, IngredientData result, List<IngredientData> ingredients) {
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.result = result;
        this.ingredients = ingredients != null ? ingredients : Collections.emptyList();
    }

    public String getRecipeId() { return recipeId; }
    public String getRecipeType() { return recipeType; }
    public IngredientData getResult() { return result; }
    public List<IngredientData> getIngredients() { return ingredients; }
}
