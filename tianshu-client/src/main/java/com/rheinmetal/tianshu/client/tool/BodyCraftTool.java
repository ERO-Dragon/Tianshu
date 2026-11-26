package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts one named recipe once, on a body's inventory.
 *
 * <p>Body side of the split pair; {@link CraftRecipeTool} is the player side. The world operation itself lives in
 * {@link CraftOps}: this tool only adapts its arguments and calls in. The ingredients come out of the body's own
 * inventory, so the actor is not optional here.</p>
 */
public final class BodyCraftTool implements TianshuTool<BodyCraftTool.Args, CraftResult> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.craft");

    public record Args(String body, String recipeId) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_WRITE;
    }

    @Override
    public String description() {
        return "Makes an agent body craft one recipe once by its recipe id, consuming the body's ingredients.";
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
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("recipeId",
                BodyToolSchemas.text("Recipe id, for example minecraft:iron_pickaxe"));
        return BodyToolSchemas.object(properties, "recipeId");
    }

    @Override
    public CompletableFuture<CraftResult> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return CraftOps.craft(scope.actor(), args.recipeId());
    }
}