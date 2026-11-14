package com.rheinmetal.tianshu.client.tool;

/**
 * The wire shape of a tag query: lower-case {@code kind} and plain ids.
 *
 * <p>It exists so the api record does not need JSON annotations: the tool deserializes this and converts to
 * {@link com.rheinmetal.tianshu.api.game.TagQuery}.</p>
 */
public final class TagQueryArgs {
    public String kind;
    public String itemId;
    public String tagId;
}
