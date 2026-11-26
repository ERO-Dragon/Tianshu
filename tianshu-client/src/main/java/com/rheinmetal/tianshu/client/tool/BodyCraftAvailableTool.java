package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.game.CraftableRecipeList;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * What an agent body could craft right now, from what it is carrying.
 *
 * <p>Body side of the split pair; {@link CraftAvailableTool} is the player side. The world operation itself lives in
 * {@link CraftAvailableOps}: this tool only adapts its arguments and calls in. The body's own inventory decides the
 * answer, so the actor is not optional here.</p>
 */
public final class BodyCraftAvailableTool implements TianshuTool<BodyCraftAvailableTool.Args, CraftableRecipeList> {

    public static final ToolId ID = ToolId.of("minecraft", "entity.craft.available");

    public record Args(String body, Integer limit) {
    }

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.BODY_READ;
    }

    @Override
    public String description() {
        return "Lists crafting recipes an agent body could make from its current inventory, with whether each one "
                + "needs a crafting table.";
    }

    @Override
    public Type argsType() {
        return Args.class;
    }

    @Override
    public Type resultType() {
        return CraftableRecipeList.class;
    }

    @Override
    public JsonObject argsSchema() {
        JsonObject properties = new JsonObject();
        properties.add("body", BodyToolSchemas.body());
        properties.add("limit",
                BodyToolSchemas.integer("Maximum recipes to report; defaults to 20, capped at 64"));
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<CraftableRecipeList> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.body(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return CraftAvailableOps.available(scope.actor(), args == null ? null : args.limit());
    }
}