package com.rheinmetal.tianshu.api.game;

/**
 * One loot-table lookup: what a block or an entity can drop.
 *
 * <p>Exactly one of {@code blockId} and {@code entityId} is set. The answer is the possible item ids, not a roll:
 * it is knowledge, not an outcome.</p>
 */
public record LootQuery(String blockId, String entityId) {
}
