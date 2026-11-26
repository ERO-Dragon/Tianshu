package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts one named recipe once, as the calling player.
 *
 * <p>Player side of the split pair; {@link BodyCraftTool} is the body side. The world operation itself lives in
 * {@link CraftOps}: this tool only adapts its arguments and calls in.</p>
 *
 * <p>A write tool: off by default, operator-only, and it only ever crafts the recipe it was given — choosing the
 * recipe is the caller's job, so a wrong assumption becomes a visible refusal instead of a surprise item.</p>
 */
public final class CraftRecipeTool implements TianshuTool<CraftRecipeTool.Args, CraftResult> {

    public record Args(String recipeId) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "craft.recipe");

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
        return Args.class;
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
    public CompletableFuture<CraftResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return CraftOps.craft(scope.actor(), args.recipeId());
    }
}