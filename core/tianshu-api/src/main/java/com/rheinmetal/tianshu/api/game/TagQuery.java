package com.rheinmetal.tianshu.api.game;

/**
 * One tag lookup, in one of two directions.
 *
 * <p>Exactly one of {@code itemId} and {@code tagId} is set: an item id asks which tags contain it, a tag id asks
 * which entries the tag holds. Both are plain ids so the answer reads the same on every platform.</p>
 *
 * @param kind   the registry the ids live in
 * @param itemId an item/block id, or {@code null}
 * @param tagId  a tag id, or {@code null}
 */
public record TagQuery(TagKind kind, String itemId, String tagId) {
}
