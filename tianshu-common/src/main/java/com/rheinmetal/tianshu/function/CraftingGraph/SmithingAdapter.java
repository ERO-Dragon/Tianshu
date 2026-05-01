package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.ArrayList;
import java.util.List;

public final class SmithingAdapter implements RecipeAdapter {

    @Override
    public boolean supports(RecipeData recipe) {
        return recipe != null && recipe.getRecipeType() != null && recipe.getRecipeType().contains("smithing");
    }

    @Override
    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        if (recipe == null) return UniversalRecipeViewModel.empty(null);
        List<SlotViewData> slots = new ArrayList<>();
        List<IngredientData> ingredients = recipe.getIngredients();
        float startX = 16.0f;
        float step = 22.0f;
        for (int i = 0; i < ingredients.size() && i < 3; i++) {
            slots.add(new SlotViewData(ingredients.get(i), SlotViewType.INPUT, startX + i * step, 30.0f));
        }
        slots.add(new SlotViewData(recipe.getResult(), SlotViewType.OUTPUT, 112.0f, 30.0f));
        return new UniversalRecipeViewModel(recipe.getRecipeId(), recipe.getRecipeType(), slots, 146.0f, 72.0f);
    }
}
