package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.TagKind;
import com.rheinmetal.tianshu.api.game.TagQuery;
import com.rheinmetal.tianshu.api.game.TagQueryPort;
import com.rheinmetal.tianshu.api.game.TagQueryResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Tag membership in either direction: which tags contain an entry, or which entries a tag holds.
 *
 * <p>Item and block tags are synced to the client, so the lookup is local; the tag registry itself differs per
 * Minecraft line and lives behind {@link TagQueryPort}, while this class owns the id, the argument validation and
 * the JSON schema.</p>
 */
public final class TagQueryTool implements TianshuTool<TagQueryArgs, TagQueryResult> {

    public static final ToolId ID = ToolId.of("tianshu", "tag.query");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.LOCAL;
    }

    @Override
    public String description() {
        return "Looks up item or block tags: give an item id to list its tags, or a tag id to list its entries.";
    }

    @Override
    public Type argsType() {
        return TagQueryArgs.class;
    }

    @Override
    public Type resultType() {
        return TagQueryResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();

        JsonObject kind = new JsonObject();
        kind.addProperty("type", "string");
        JsonArray kindValues = new JsonArray();
        kindValues.add("item");
        kindValues.add("block");
        kind.add("enum", kindValues);
        kind.addProperty("description", "Which registry the ids belong to; defaults to item");
        properties.add("kind", kind);

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "An item or block id; lists the tags that contain it");
        properties.add("itemId", item);

        JsonObject tag = new JsonObject();
        tag.addProperty("type", "string");
        tag.addProperty("description", "A tag id; lists its entries");
        properties.add("tagId", tag);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("description", "Set exactly one of itemId and tagId.");
        return schema;
    }

    @Override
    public CompletableFuture<TagQueryResult> invoke(ToolContext context, TagQueryArgs args) {
        TagKind kind = kindOf(args == null ? null : args.kind);
        boolean byItem = args != null && notBlank(args.itemId);
        boolean byTag = args != null && notBlank(args.tagId);
        if (kind == null) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "tag.query needs a kind: item or block"));
        }
        if (byItem == byTag) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.INVALID_ARGS,
                    "tag.query needs exactly one of itemId and tagId"));
        }
        TagQuery normalised = byItem
                ? new TagQuery(kind, args.itemId.trim(), null)
                : new TagQuery(kind, null, args.tagId.trim());
        return TianshuRuntimePorts.find(TagQueryPort.class)
                .map(port -> port.query(normalised))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "this platform does not provide a tag source")));
    }

    private static TagKind kindOf(String raw) {
        if (raw == null) {
            return TagKind.ITEM;
        }
        return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "item" -> TagKind.ITEM;
            case "block" -> TagKind.BLOCK;
            default -> null;
        };
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}