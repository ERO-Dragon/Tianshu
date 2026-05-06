package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
    private String selectedRecipeCategory;
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
        RecipeData recipe = recipes.get(index);
        if (selectedRecipeCategory == null || recipeMatchesCategory(recipe, selectedRecipeCategory)) return recipe;
        List<Integer> indices = filteredRecipeIndices();
        if (indices.isEmpty()) return recipe;
        selectedRecipeIndex = indices.get(0);
        return recipes.get(selectedRecipeIndex);
    }

    public void nextRecipe() {
        stepRecipe(1);
    }

    public void previousRecipe() {
        stepRecipe(-1);
    }

    public void setSelectedRecipeIndex(int selectedRecipeIndex) {
        if (!canSwitchRecipe() || recipes.isEmpty()) return;
        int index = Math.max(0, Math.min(selectedRecipeIndex, recipes.size() - 1));
        RecipeData recipe = recipes.get(index);
        if (selectedRecipeCategory != null && !recipeMatchesCategory(recipe, selectedRecipeCategory)) return;
        this.selectedRecipeIndex = index;
        alignCategoryToSelectedRecipe();
    }

    public List<String> recipeCategories() {
        Set<String> categories = new LinkedHashSet<>();
        for (RecipeData recipe : recipes) categories.add(recipeCategory(recipe));
        return new ArrayList<>(categories);
    }

    public List<Integer> filteredRecipeIndices() {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            if (selectedRecipeCategory == null || recipeMatchesCategory(recipes.get(i), selectedRecipeCategory)) indices.add(i);
        }
        return indices;
    }

    public boolean hasMultipleRecipesInSelectedCategory() {
        return filteredRecipeIndices().size() > 1;
    }

    public void selectRecipeCategory(String recipeCategory) {
        if (!canSwitchRecipe() || recipes.isEmpty()) return;
        String category = normalizeCategory(recipeCategory);
        if (category == null) return;
        for (int i = 0; i < recipes.size(); i++) {
            if (recipeMatchesCategory(recipes.get(i), category)) {
                selectedRecipeCategory = category;
                selectedRecipeIndex = i;
                recipePickerScroll = 0.0f;
                return;
            }
        }
    }

    public static String recipeCategory(RecipeData recipe) {
        String type = recipe != null ? recipe.getRecipeType() : null;
        return normalizeCategory(type) != null ? normalizeCategory(type) : "unknown";
    }

    private static String normalizeCategory(String recipeCategory) {
        return recipeCategory != null && !recipeCategory.isBlank() ? recipeCategory : null;
    }

    private boolean recipeMatchesCategory(RecipeData recipe, String recipeCategory) {
        return recipeCategory != null && recipeCategory.equals(recipeCategory(recipe));
    }

    private void alignCategoryToSelectedRecipe() {
        selectedRecipeCategory = recipeCategory(getSelectedRecipe());
    }

    private void stepRecipe(int delta) {
        if (!canSwitchRecipe() || recipes.isEmpty()) return;
        List<Integer> indices = filteredRecipeIndices();
        if (indices.isEmpty()) return;
        int current = indices.indexOf(Math.max(0, Math.min(selectedRecipeIndex, recipes.size() - 1)));
        if (current < 0) current = 0;
        int next = (current + delta) % indices.size();
        if (next < 0) next += indices.size();
        selectedRecipeIndex = indices.get(next);
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
    public String getSelectedRecipeCategory() { return selectedRecipeCategory; }
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
