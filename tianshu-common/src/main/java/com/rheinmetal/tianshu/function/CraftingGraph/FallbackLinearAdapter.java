package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.ArrayList;
import java.util.List;

public final class FallbackLinearAdapter implements RecipeAdapter {

    @Override
    public boolean supports(RecipeData recipe) {
        return recipe != null;
    }

    @Override
    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        if (recipe == null) return UniversalRecipeViewModel.empty(null);

        List<SlotViewData> slots = new ArrayList<>();
        List<IngredientData> ingredients = recipe.getIngredients();
        float inputX = 18.0f;
        float inputY = 18.0f;
        float step = 20.0f;
        int maxPerColumn = 3;

        for (int i = 0; i < ingredients.size(); i++) {
            int col = i / maxPerColumn;
            int row = i % maxPerColumn;
            slots.add(new SlotViewData(ingredients.get(i), SlotViewType.INPUT, inputX + col * step, inputY + row * step));
        }

        slots.add(new SlotViewData(recipe.getResult(), SlotViewType.OUTPUT, 116.0f, 38.0f));
        return new UniversalRecipeViewModel(recipe.getRecipeId(), recipe.getRecipeType(), slots, 150.0f, 82.0f);
    }
}
