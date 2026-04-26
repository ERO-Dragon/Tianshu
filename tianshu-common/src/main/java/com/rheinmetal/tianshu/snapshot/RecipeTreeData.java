package com.rheinmetal.tianshu.snapshot;

import java.util.*;

public final class RecipeTreeData {

    public final String targetItemId;
    public final List<RecipeData> recipes;

    public RecipeTreeData(String targetItemId, List<RecipeData> recipes) {
        this.targetItemId = targetItemId;
        this.recipes = recipes != null ? recipes : Collections.emptyList();
    }

    public String getTargetItemId() { return targetItemId; }
    public List<RecipeData> getRecipes() { return recipes; }
}
