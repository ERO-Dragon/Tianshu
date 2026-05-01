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
        return type.contains("crafting") && recipe.getIngredients().size() <= 9;
    }

    @Override
    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        if (recipe == null) return UniversalRecipeViewModel.empty(null);

        List<SlotViewData> slots = new ArrayList<>();
        List<IngredientData> ingredients = recipe.getIngredients();
        float startX = 18.0f;
        float startY = 18.0f;
        float step = 20.0f;

        for (int i = 0; i < ingredients.size() && i < 9; i++) {
            int col = i % 3;
            int row = i / 3;
            slots.add(new SlotViewData(ingredients.get(i), SlotViewType.INPUT, startX + col * step, startY + row * step));
        }

        slots.add(new SlotViewData(recipe.getResult(), SlotViewType.OUTPUT, 112.0f, 38.0f));
        return new UniversalRecipeViewModel(recipe.getRecipeId(), recipe.getRecipeType(), slots, 146.0f, 82.0f);
    }
}
