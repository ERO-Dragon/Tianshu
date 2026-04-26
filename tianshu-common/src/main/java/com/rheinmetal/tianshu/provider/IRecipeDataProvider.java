package com.rheinmetal.tianshu.provider;

import com.rheinmetal.tianshu.snapshot.RecipeTreeData;

public interface IRecipeDataProvider {
    RecipeTreeData getRecipeTree(String itemId);
}
