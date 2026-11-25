package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.CraftableRecipe;
import com.rheinmetal.tianshu.api.game.CraftableRecipeList;
import com.rheinmetal.tianshu.api.game.RecipeAvailabilityPort;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 reverse recipe read: every crafting recipe the actor's inventory can pay for.
 *
 * <p>Satisfiability is decided by {@link CraftSource#assignIngredients}, the same routine {@code craft.recipe} uses to
 * consume them. That is the point: a "you can make this" that disagrees with what the crafting tool actually accepts
 * would be worse than no answer, and the only way to keep them in step is to share the routine.</p>
 */
public final class CraftAvailableSource implements RecipeAvailabilityPort {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 64;

    private final MinecraftServer server;

    public CraftAvailableSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<CraftableRecipeList> available(ActorRef actor, int limit) {
        CompletableFuture<CraftableRecipeList> result = new CompletableFuture<>();
        server.execute(() -> result.complete(availableNow(actor, limit)));
        return result;
    }

    private CraftableRecipeList availableNow(ActorRef actor, int limit) {
        int bounded = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null || holder.inventory() == null) {
            return new CraftableRecipeList(List.of(), bounded);
        }
        List<CraftableRecipe> craftable = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> held : server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = held.value();
            List<Ingredient> required = requiredIngredients(recipe);
            if (required.isEmpty() || CraftSource.assignIngredients(holder.inventory(), required) == null) {
                continue;
            }
            ItemStack output = recipe.getResultItem(server.registryAccess());
            if (output.isEmpty()) {
                continue;
            }
            ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.getItem());
            craftable.add(new CraftableRecipe(
                    held.id().toString(),
                    outputId == null ? "" : outputId.toString(),
                    output.getCount(),
                    needsTable(recipe, required.size())));
            if (craftable.size() >= bounded) {
                break;
            }
        }
        return new CraftableRecipeList(craftable, bounded);
    }

    /** Empty ingredients are the padding of a shaped grid; a recipe is paid for by its non-empty slots only. */
    private static List<Ingredient> requiredIngredients(CraftingRecipe recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        List<Ingredient> required = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                required.add(ingredient);
            }
        }
        return required;
    }

    /**
     * Whether a player's own 2x2 grid cannot hold this one.
     *
     * <p>Shaped recipes are decided by their declared width and height, because a 2x3 recipe cannot be rotated into a
     * 2x2 grid; shapeless ones by their count, because four slots is what the grid has.</p>
     */
    private static boolean needsTable(CraftingRecipe recipe, int requiredSlots) {
        if (recipe instanceof ShapedRecipe shaped) {
            return shaped.getWidth() > 2 || shaped.getHeight() > 2;
        }
        return requiredSlots > 4;
    }
}