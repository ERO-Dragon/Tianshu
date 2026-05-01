package com.rheinmetal.tianshu.function.CraftingGraph;

import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.ItemSnapshot;
import com.rheinmetal.tianshu.snapshot.RecipeData;

import java.util.List;

public final class InventoryAnalyzer {

    public int bestRecipeIndex(List<RecipeData> recipes, List<ItemSnapshot> inventoryItems) {
        if (recipes == null || recipes.isEmpty()) return 0;
        int bestIndex = 0;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < recipes.size(); i++) {
            int score = scoreRecipe(recipes.get(i), inventoryItems);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public int availableCount(IngredientData ingredient, List<ItemSnapshot> inventoryItems) {
        if (ingredient == null || inventoryItems == null || inventoryItems.isEmpty()) return 0;
        int count = 0;
        for (ItemSnapshot item : inventoryItems) {
            if (matchesIngredient(ingredient, item)) count += Math.max(0, item.getCount());
        }
        return count;
    }

    public String bestAvailableItemId(IngredientData ingredient, List<ItemSnapshot> inventoryItems) {
        if (ingredient == null || inventoryItems == null || inventoryItems.isEmpty()) return null;
        String bestItemId = null;
        int bestCount = 0;
        for (ItemSnapshot item : inventoryItems) {
            if (matchesIngredient(ingredient, item) && item.getCount() > bestCount) {
                bestItemId = item.getItemId();
                bestCount = item.getCount();
            }
        }
        return bestItemId;
    }

    public int requiredCount(IngredientData ingredient) {
        if (ingredient == null) return 1;
        return Math.max(1, ingredient.getCount());
    }

    private int scoreRecipe(RecipeData recipe, List<ItemSnapshot> inventoryItems) {
        if (recipe == null || recipe.getIngredients() == null || recipe.getIngredients().isEmpty()) return 0;
        int score = 0;
        for (IngredientData ingredient : recipe.getIngredients()) {
            int required = requiredCount(ingredient);
            int available = availableCount(ingredient, inventoryItems);
            score += Math.min(available, required) * 100;
            if (available >= required) score += 10000;
        }
        return score;
    }

    private boolean matchesIngredient(IngredientData ingredient, ItemSnapshot item) {
        if (ingredient == null || item == null || item.getItemId() == null) return false;
        String itemId = normalizedItemId(ingredient.getItemId());
        if (itemId != null && !itemId.isBlank() && !itemId.startsWith("#") && itemId.equals(item.getItemId())) return true;
        return ingredient.getTagItems().contains(item.getItemId());
    }

    private String normalizedItemId(String itemId) {
        if (itemId == null) return null;
        int separator = itemId.indexOf('/');
        return separator >= 0 ? itemId.substring(0, separator) : itemId;
    }
}
