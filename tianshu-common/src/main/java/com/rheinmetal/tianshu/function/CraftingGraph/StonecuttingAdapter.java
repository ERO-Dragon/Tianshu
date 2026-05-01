package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.ArrayList;
import java.util.List;

public final class StonecuttingAdapter implements RecipeAdapter {

    @Override
    public boolean supports(RecipeData recipe) {
        return recipe != null && recipe.getRecipeType() != null && recipe.getRecipeType().contains("stonecutting");
    }

    @Override
    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        if (recipe == null) return UniversalRecipeViewModel.empty(null);
        List<SlotViewData> slots = new ArrayList<>();
        List<IngredientData> ingredients = recipe.getIngredients();
        if (!ingredients.isEmpty()) slots.add(new SlotViewData(ingredients.get(0), SlotViewType.INPUT, 26.0f, 30.0f));
        slots.add(new SlotViewData(recipe.getResult(), SlotViewType.OUTPUT, 104.0f, 30.0f));
        return new UniversalRecipeViewModel(recipe.getRecipeId(), recipe.getRecipeType(), slots, 138.0f, 72.0f);
    }
}
