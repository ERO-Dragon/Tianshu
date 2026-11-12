package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts one named recipe once, as the requesting player.
 *
 * <p>A write tool: off by default, operator-only, and it only ever crafts the recipe it was given — choosing the
 * recipe is the caller's job, so a wrong assumption becomes a visible refusal instead of a surprise item.</p>
 */
public final class CraftRecipeTool implements TianshuTool<CraftRequest, CraftResult> {

    public static final ToolId ID = ToolId.of("tianshu", "craft.recipe");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_WRITE;
    }

    @Override
    public String description() {
        return "Crafts one recipe once by its recipe id, consuming the player's ingredients.";
    }

    @Override
    public Type argsType() {
        return CraftRequest.class;
    }

    @Override
    public Type resultType() {
        return CraftResult.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", "string");
        recipe.addProperty("description", "Recipe id, for example minecraft:iron_pickaxe");

        JsonObject properties = new JsonObject();
        properties.add("recipeId", recipe);

        JsonArray required = new JsonArray();
        required.add("recipeId");

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        return schema;
    }

    @Override
    public CompletableFuture<CraftResult> invoke(ToolContext context, CraftRequest args) {
        ToolCaller caller = context.caller();
        if (caller.kind() != ToolCaller.Kind.PLAYER) {
            return CompletableFuture.failedFuture(new ToolException(ToolError.NO_PERMISSION,
                    "craft.recipe runs as a player"));
        }
        if (args == null || args.recipeId() == null || args.recipeId().isBlank()) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.INVALID_ARGS, "craft.recipe needs a recipe id"));
        }
        return TianshuRuntimePorts.find(CraftPort.class)
                .map(port -> port.craft(caller.id(), new CraftRequest(args.recipeId().trim())))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "crafting is only available on the server")));
    }
}