package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.entity.ActorRef;
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
 * What you could craft right now, from what you are carrying.
 *
 * <p>Player side of the split pair; {@link BodyCraftAvailableTool} is the body side and owns the shared call in
 * {@link #available(ActorRef, Args)}.</p>
 *
 * <p>The reverse of {@code recipe.query}. A caller that does not know an item id cannot use a forward recipe lookup at
 * all, and guessing ids is the failure mode this exists for.</p>
 *
 * <p>Atomic: it reports possibilities. Crafting is {@code craft.recipe}; gathering what is missing is a workflow and
 * belongs to a module.</p>
 */
public final class CraftAvailableTool implements TianshuTool<CraftAvailableTool.Args, CraftableRecipeList> {

    public record Args(Integer limit) {
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
        return "Lists crafting recipes you could make from your current inventory, with whether each one "
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
        return BodyToolSchemas.object(properties);
    }

    @Override
    public CompletableFuture<CraftableRecipeList> invoke(ToolContext context, Args args) {
        ActorScope scope = ActorScope.player(context.caller());
        if (scope.refusal().isPresent()) {
            return CompletableFuture.failedFuture(scope.refusal().get());
        }
        return available(scope.actor(), args);
    }

    /** Shared by both sides of the pair: the actor decides whose inventory is read. */
    static CompletableFuture<CraftableRecipeList> available(ActorRef actor, Args args) {
        int limit = args == null || args.limit() == null ? 0 : args.limit();
        return TianshuRuntimePorts.find(RecipeAvailabilityPort.class)
                .map(port -> port.available(actor, limit))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "recipe availability is only available on the server")));
    }
}