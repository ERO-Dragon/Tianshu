package com.rheinmetal.tianshu.api.game;

/**
 * One recipe lookup: which recipes produce an item.
 *
 * @param outputItemId the item id to search for, for example {@code minecraft:iron_pickaxe}
 * @param limit        the maximum number of entries to return; the tool clamps it to a sane range
 */
public record RecipeQuery(String outputItemId, int limit) {
}