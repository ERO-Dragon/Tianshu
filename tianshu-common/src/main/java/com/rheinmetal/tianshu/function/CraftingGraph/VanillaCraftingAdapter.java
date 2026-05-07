package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.ArrayList;
import java.util.List;

public final class VanillaCraftingAdapter implements RecipeAdapter {

    @Override
    public boolean supports(RecipeData recipe) {
        if (recipe == null || recipe.getRecipeType() == null) return false;
        String type = recipe.getRecipeType();
        return type.contains("crafting") && (recipe.hasCraftingGrid() || recipe.getIngredients().size() <= 9);
    }

    @Override
    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        if (recipe == null) return UniversalRecipeViewModel.empty(null);

        List<SlotViewData> slots = new ArrayList<>();
        float startX = 18.0f;
        float startY = 18.0f;
        float step = 20.0f;

        if (recipe.hasCraftingGrid()) {
            List<IngredientData> grid = recipe.getCraftingGrid();
            int width = Math.min(3, recipe.getCraftingGridWidth());
            int height = Math.min(3, recipe.getCraftingGridHeight());
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int index = row * recipe.getCraftingGridWidth() + col;
                    if (index < 0 || index >= grid.size()) continue;
                    IngredientData ingredient = grid.get(index);
                    if (ingredient == null) continue;
                    slots.add(new SlotViewData(ingredient, SlotViewType.INPUT, startX + col * step, startY + row * step));
                }
            }
        } else {
            List<IngredientData> ingredients = recipe.getIngredients();
            for (int i = 0; i < ingredients.size() && i < 9; i++) {
                int col = i % 3;
                int row = i / 3;
                slots.add(new SlotViewData(ingredients.get(i), SlotViewType.INPUT, startX + col * step, startY + row * step));
            }
        }

        slots.add(new SlotViewData(recipe.getResult(), SlotViewType.OUTPUT, 104.0f, 38.0f));
        return new UniversalRecipeViewModel(recipe.getRecipeId(), recipe.getRecipeType(), slots, 138.0f, 82.0f);
    }
}
