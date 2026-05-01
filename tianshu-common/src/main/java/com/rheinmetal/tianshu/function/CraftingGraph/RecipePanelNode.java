package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class RecipePanelNode {

    private final UUID uuid;
    private final UUID parentUuid;
    private final RecipePanelNodeType nodeType;
    private final String itemId;
    private final String displayName;
    private final List<RecipeData> recipes;
    private int selectedRecipeIndex;
    private float x;
    private float y;
    private boolean inputAnchorsLocked;
    private boolean outputAnchorsLocked;
    private boolean recipePickerOpen;
    private float recipePickerScroll;
    private long highlightUntilMillis;

    public RecipePanelNode(UUID uuid, UUID parentUuid, RecipePanelNodeType nodeType, String itemId, String displayName, List<RecipeData> recipes, float x, float y) {
        this.uuid = uuid;
        this.parentUuid = parentUuid;
        this.nodeType = nodeType != null ? nodeType : RecipePanelNodeType.SOURCE;
        this.itemId = itemId;
        this.displayName = displayName;
        this.recipes = recipes != null ? Collections.unmodifiableList(recipes) : Collections.emptyList();
        this.x = x;
        this.y = y;
    }

    public RecipeData getSelectedRecipe() {
        if (recipes.isEmpty()) return null;
        int index = Math.max(0, Math.min(selectedRecipeIndex, recipes.size() - 1));
        return recipes.get(index);
    }

    public void nextRecipe() {
        if (canSwitchRecipe() && !recipes.isEmpty()) selectedRecipeIndex = (selectedRecipeIndex + 1) % recipes.size();
    }

    public void previousRecipe() {
        if (canSwitchRecipe() && !recipes.isEmpty()) selectedRecipeIndex = (selectedRecipeIndex + recipes.size() - 1) % recipes.size();
    }

    public void setSelectedRecipeIndex(int selectedRecipeIndex) {
        if (canSwitchRecipe() && !recipes.isEmpty()) this.selectedRecipeIndex = Math.max(0, Math.min(selectedRecipeIndex, recipes.size() - 1));
    }

    public void highlight(long nowMillis, long durationMillis) {
        highlightUntilMillis = nowMillis + durationMillis;
    }

    public UUID getUuid() { return uuid; }
    public UUID getParentUuid() { return parentUuid; }
    public RecipePanelNodeType getNodeType() { return nodeType; }
    public String getItemId() { return itemId; }
    public String getDisplayName() { return displayName; }
    public List<RecipeData> getRecipes() { return recipes; }
    public int getSelectedRecipeIndex() { return selectedRecipeIndex; }
    public float getX() { return x; }
    public float getY() { return y; }
    public boolean isInputAnchorsLocked() { return inputAnchorsLocked; }
    public boolean isOutputAnchorsLocked() { return outputAnchorsLocked; }
    public boolean isLayoutLocked() { return !canSwitchRecipe(); }
    public boolean canSwitchRecipe() { return !inputAnchorsLocked && !outputAnchorsLocked; }
    public boolean canSwitchInputSide() { return !inputAnchorsLocked; }
    public boolean canSwitchOutputSide() { return !outputAnchorsLocked; }
    public boolean isRecipePickerOpen() { return recipePickerOpen; }
    public float getRecipePickerScroll() { return recipePickerScroll; }
    public boolean isHighlighted(long nowMillis) { return nowMillis < highlightUntilMillis; }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    public void setInputAnchorsLocked(boolean inputAnchorsLocked) {
        this.inputAnchorsLocked = inputAnchorsLocked;
    }

    public void setOutputAnchorsLocked(boolean outputAnchorsLocked) {
        this.outputAnchorsLocked = outputAnchorsLocked;
    }

    public void setRecipePickerOpen(boolean recipePickerOpen) {
        this.recipePickerOpen = recipePickerOpen;
    }

    public void setRecipePickerScroll(float recipePickerScroll) {
        this.recipePickerScroll = Math.max(0.0f, recipePickerScroll);
    }

    public void setLayoutLocked(boolean layoutLocked) {
        this.inputAnchorsLocked = layoutLocked;
        this.outputAnchorsLocked = layoutLocked;
    }
}
