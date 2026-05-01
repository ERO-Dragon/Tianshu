package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.List;

public final class RecipeAdapterRegistry {

    private final List<RecipeAdapter> adapters = List.of(
            new StonecuttingAdapter(),
            new SmithingAdapter(),
            new VanillaCraftingAdapter(),
            new FallbackLinearAdapter()
    );

    public UniversalRecipeViewModel adapt(RecipeData recipe) {
        for (RecipeAdapter adapter : adapters) {
            if (adapter.supports(recipe)) {
                return adapter.adapt(recipe);
            }
        }
        return UniversalRecipeViewModel.empty(recipe);
    }
}
