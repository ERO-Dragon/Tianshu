package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.RecipeQuery;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import com.rheinmetal.tianshu.api.game.RecipeQueryResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * The first real tool: which recipes produce an item.
 *
 * <p>It is a thin contract on purpose. The recipe API differs per Minecraft line, so the lookup lives behind
 * {@link RecipeQueryPort} and each platform layer implements it once; this class owns the id, the policy, the
 * argument clamp and the JSON schema, so every version answers the same way.</p>
 */
public final class RecipeQueryTool implements TianshuTool<RecipeQuery, RecipeQueryResult> {

    public static final ToolId ID = ToolId.of("tianshu", "recipe.query");
    public static final int MAX_LIMIT = 64;

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
        return "Lists recipes that produce an item, given that item's id.";
    }

    @Override
    public Type argsType() {
        return RecipeQuery.class;
    }

    @Override
    public Type resultType() {
        return RecipeQueryResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();

        JsonObject item = new JsonObject();
        item.addProperty("type", "string");
        item.addProperty("description", "Item id to search for, for example minecraft:iron_pickaxe");
        properties.add("outputItemId", item);

        JsonObject limit = new JsonObject();
        limit.addProperty("type", "integer");
        limit.addProperty("minimum", 1);
        limit.addProperty("maximum", MAX_LIMIT);
        limit.addProperty("description", "Maximum number of recipes to return; clamped to 1..64");
        properties.add("limit", limit);

        JsonArray required = new JsonArray();
        required.add("outputItemId");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<RecipeQueryResult> invoke(ToolContext context, RecipeQuery args) {
        if (args == null || args.outputItemId() == null || args.outputItemId().isBlank()) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.INVALID_ARGS, "recipe.query needs an item id"));
        }
        RecipeQuery normalised = new RecipeQuery(args.outputItemId().trim(), clampLimit(args.limit()));
        return TianshuRuntimePorts.find(RecipeQueryPort.class)
                .map(port -> port.query(normalised).thenApply(RecipeQueryResult::new))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "this platform does not provide a recipe source")));
    }

    static int clampLimit(int requested) {
        return Math.max(1, Math.min(MAX_LIMIT, requested));
    }
}