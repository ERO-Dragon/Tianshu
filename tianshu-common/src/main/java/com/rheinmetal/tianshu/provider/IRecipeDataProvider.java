package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.RecipeTreeData;

import java.util.Collections;

public interface IRecipeDataProvider {
    RecipeTreeData getRecipeTree(String itemId);

    default RecipeTreeData getUsageTree(String itemId) {
        return new RecipeTreeData(itemId, Collections.emptyList());
    }
}
