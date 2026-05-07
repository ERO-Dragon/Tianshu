package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class RecipeData {

    public final String recipeId;
    public final String recipeType;
    public final IngredientData result;
    public final List<IngredientData> ingredients;
    public final List<IngredientData> craftingGrid;
    public final int craftingGridWidth;
    public final int craftingGridHeight;

    public RecipeData(String recipeId, String recipeType, IngredientData result, List<IngredientData> ingredients) {
        this(recipeId, recipeType, result, ingredients, null, 0, 0);
    }

    public RecipeData(String recipeId, String recipeType, IngredientData result, List<IngredientData> ingredients,
                      List<IngredientData> craftingGrid, int craftingGridWidth, int craftingGridHeight) {
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.result = result;
        this.ingredients = ingredients != null ? ingredients : Collections.emptyList();
        this.craftingGrid = craftingGrid != null ? Collections.unmodifiableList(craftingGrid) : Collections.emptyList();
        this.craftingGridWidth = Math.max(0, craftingGridWidth);
        this.craftingGridHeight = Math.max(0, craftingGridHeight);
    }

    public String getRecipeId() { return recipeId; }
    public String getRecipeType() { return recipeType; }
    public IngredientData getResult() { return result; }
    public List<IngredientData> getIngredients() { return ingredients; }
    public List<IngredientData> getCraftingGrid() { return craftingGrid; }
    public int getCraftingGridWidth() { return craftingGridWidth; }
    public int getCraftingGridHeight() { return craftingGridHeight; }
    public boolean hasCraftingGrid() {
        return craftingGridWidth > 0 && craftingGridHeight > 0 && craftingGrid.size() >= craftingGridWidth * craftingGridHeight;
    }
}
