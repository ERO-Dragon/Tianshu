package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.CraftableRecipe;
import com.rheinmetal.tianshu.api.game.CraftableRecipeList;
import com.rheinmetal.tianshu.api.game.RecipeAvailabilityPort;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;
import com.rheinmetal.tianshu.api.tool.ToolPolicy;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * What the acting body could craft right now, from what it is carrying.
 *
 * <p>The reverse of {@code recipe.query}. A caller that does not know an item id cannot use a forward recipe lookup at
 * all, and guessing ids is the failure mode this exists for.</p>
 *
 * <p>Atomic: it reports possibilities. Crafting is {@code craft.recipe}; gathering what is missing is a workflow and
 * belongs to a module.</p>
 */
public final class CraftAvailableTool implements TianshuTool<CraftAvailableTool.Args, CraftableRecipeList> {

    /** {@code body} is optional: without it the caller's own inventory is read. */
    public record Args(Integer limit, String body) {
    }

    public static final ToolId ID = ToolId.of("minecraft", "craft.available");

    @Override
    public ToolId id() {
        return ID;
    }

    @Override
    public ToolCategory category() {
        return ToolCategory.SERVER_READ;
    }

    @Override
    public ToolPolicy policy() {
        return ToolPolicy.forCategory(ToolCategory.SERVER_READ).withEnabledByDefault(true);
    }

    @Override
    public String description() {
        return "Lists crafting recipes the acting body could make from its current inventory, with whether each one "
                + "needs a crafting table. Use craft.recipe to actually craft one.";
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
        properties.add("limit", BodyToolSchemas.integer("Maximum recipes to report; defaults to 20, capped at 64"));
        properties.add("body", BodyToolSchemas.body());
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<CraftableRecipeList> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.of(context.caller(), args == null ? null : args.body());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        int limit = args == null || args.limit() == null ? 0 : args.limit();
        return TianshuRuntimePorts.find(RecipeAvailabilityPort.class)
                .map(port -> port.available(scope.actor(), limit))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "recipe availability is only available on the server")));
    }
}