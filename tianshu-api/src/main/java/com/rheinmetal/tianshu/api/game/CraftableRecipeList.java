package com.rheinmetal.tianshu.api.game;

import java.util.List;

/**
 * The tool result of {@code minecraft:craft.available}; a wrapper so the result type stays a plain class.
 *
 * <p>{@code limit} is echoed back because a caller that asked for 20 and received 20 cannot otherwise tell a complete
 * list from a truncated one — and the difference decides whether it should ask again for more.</p>
 */
public record CraftableRecipeList(List<CraftableRecipe> recipes, int limit) {

    public CraftableRecipeList {
        recipes = recipes == null ? List.of() : List.copyOf(recipes);
    }

    /** True when the answer hit the cap and there may be more. */
    public boolean truncated() {
        return limit > 0 && recipes.size() >= limit;
    }
}