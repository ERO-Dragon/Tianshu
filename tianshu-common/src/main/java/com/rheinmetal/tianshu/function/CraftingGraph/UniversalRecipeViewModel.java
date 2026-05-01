package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.Collections;
import java.util.List;

public final class UniversalRecipeViewModel {

    private final String recipeId;
    private final String recipeType;
    private final List<SlotViewData> slots;
    private final float width;
    private final float height;

    public UniversalRecipeViewModel(String recipeId, String recipeType, List<SlotViewData> slots, float width, float height) {
        this.recipeId = recipeId;
        this.recipeType = recipeType;
        this.slots = slots != null ? Collections.unmodifiableList(slots) : Collections.emptyList();
        this.width = width;
        this.height = height;
    }

    public static UniversalRecipeViewModel empty(RecipeData recipe) {
        String id = recipe != null ? recipe.getRecipeId() : "empty";
        String type = recipe != null ? recipe.getRecipeType() : "empty";
        return new UniversalRecipeViewModel(id, type, Collections.emptyList(), 128.0f, 64.0f);
    }

    public String getRecipeId() { return recipeId; }
    public String getRecipeType() { return recipeType; }
    public List<SlotViewData> getSlots() { return slots; }
    public float getWidth() { return width; }
    public float getHeight() { return height; }
}
