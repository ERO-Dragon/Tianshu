package com.rheinmetal.tianshu.client.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.entity.EntityActors;
import com.rheinmetal.tianshu.api.entity.EntityCapability;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import com.rheinmetal.tianshu.api.tool.TianshuTool;
import com.rheinmetal.tianshu.api.tool.ToolCategory;
import com.rheinmetal.tianshu.api.tool.ToolContext;
import com.rheinmetal.tianshu.api.tool.ToolError;
import com.rheinmetal.tianshu.api.tool.ToolException;
import com.rheinmetal.tianshu.api.tool.ToolId;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

/**
 * Crafts one named recipe once, as the calling player.
 *
 * <p>Player side of the split pair; {@link BodyCraftTool} is the body side and owns the shared call in
 * {@link #craft(ActorRef, Args)}.</p>
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
    public com.rheinmetal.tianshu.api.tool.ToolPolicy policy() {
        // Any survival player may do this by hand; the server switch and their own rights still bound it.
        // On by default: this is a player's own crafting or container work, and their real rights still apply.
        return com.rheinmetal.tianshu.api.tool.ToolPolicy.forCategory(ToolCategory.SERVER_WRITE)
                .withPermission(com.rheinmetal.tianshu.api.tool.ToolPermission.EVERYONE)
                .withEnabledByDefault(true);
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
        return craft(scope.actor(), args);
    }

    /** Shared by both sides of the pair: the actor decides whose ingredients are consumed. */
    static CompletableFuture<CraftResult> craft(ActorRef actor, Args args) {
        if (args == null || args.recipeId() == null || args.recipeId().isBlank()) {
            return CompletableFuture.failedFuture(
                    new ToolException(ToolError.INVALID_ARGS, "craft.recipe needs a recipe id"));
        }
        java.util.Optional<ToolException> refusal = EntityActors.checkCapability(actor, EntityCapability.INVENTORY);
        if (refusal.isPresent()) {
            return CompletableFuture.failedFuture(refusal.get());
        }
        return TianshuRuntimePorts.find(CraftPort.class)
                .map(port -> port.craft(actor, new CraftRequest(args.recipeId().trim()))
                        .thenCompose(result -> result.crafted()
                                ? CompletableFuture.completedFuture(result)
                                : CompletableFuture.failedFuture(new ToolException(ToolError.FAILED,
                                        result.message().isBlank() ? "crafting failed" : result.message()))))
                .orElseGet(() -> CompletableFuture.failedFuture(new ToolException(ToolError.UNAVAILABLE,
                        "crafting is only available on the server")));
    }
}