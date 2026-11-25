package com.rheinmetal.tianshu.api.game;

/**
 * One recipe the actor's current inventory can pay for.
 *
 * <p>{@code needsTable} is part of the answer, not something the caller should have to work out: a 2x2 recipe fits a
 * player's own grid while a 3x3 one needs a crafting table nearby, and a list that mixes both without saying which is
 * which invites a caller to walk to a table for a recipe that never needed one — or worse, to try a 3x3 recipe standing
 * in a field.</p>
 */
public record CraftableRecipe(String recipeId, String outputItemId, int outputCount, boolean needsTable) {

    public CraftableRecipe {
        recipeId = recipeId == null ? "" : recipeId;
        outputItemId = outputItemId == null ? "" : outputItemId;
    }
}