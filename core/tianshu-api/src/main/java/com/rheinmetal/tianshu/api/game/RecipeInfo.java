package com.rheinmetal.tianshu.api.game;

import java.util.List;

/**
 * One recipe, flattened for a tool result.
 *
 * <p>The shape is deliberately textual: ids rather than game objects, so a client-side implementation and a
 * future server-side one produce the same answer and the agent can read it without a second lookup.</p>
 *
 * @param id      the recipe id, for example {@code minecraft:iron_pickaxe}
 * @param type    the recipe type id, for example {@code minecraft:crafting_shaped}
 * @param inputs  item ids the recipe consumes, in the recipe's own order
 * @param output  item id the recipe produces
 */
public record RecipeInfo(String id, String type, List<String> inputs, String output) {
}