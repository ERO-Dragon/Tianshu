package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.entity.ActorRef;
import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 crafting: consumes one item per ingredient and hands the result to the actor.
 *
 * <p>Rules kept deliberately explicit: only crafting-table recipes are accepted, the actor must stand near a
 * crafting table, and the whole call is all-or-nothing — if one ingredient is missing, nothing is consumed. A
 * player and an agent body follow the same rules; only the inventory behind them differs.</p>
 */
public final class CraftSource implements CraftPort {

    private static final int CRAFTING_TABLE_RADIUS = 4;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;

    public CraftSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    @Deprecated
    public CompletableFuture<CraftResult> craft(String playerId, CraftRequest request) {
        return craft(ActorRef.player(playerId, false), request);
    }

    @Override
    public CompletableFuture<CraftResult> craft(ActorRef actor, CraftRequest request) {
        return CompletableFuture.supplyAsync(() -> craftNow(actor, request), server);
    }

    private CraftResult craftNow(ActorRef actor, CraftRequest request) {
        ToolActor holder = ToolActors.resolve(server, actor).orElse(null);
        if (holder == null) {
            return failure(actor.kind() == ActorRef.Kind.BODY
                    ? "the agent body is not present"
                    : "the player is not online");
        }
        Container inventory = holder.inventory();
        if (inventory == null) {
            return failure("that agent body has no inventory");
        }
        ServerLevel level = holder.level();
        ResourceLocation id = ResourceLocation.tryParse(request.recipeId());
        if (id == null) {
            return failure("that is not a recipe id");
        }
        Optional<RecipeHolder<?>> found = level.getRecipeManager().byKey(id);
        if (found.isEmpty()) {
            return failure("no such recipe");
        }
        Recipe<?> recipe = found.get().value();
        if (!(recipe instanceof CraftingRecipe crafting)) {
            return failure("only crafting-table recipes are supported");
        }
        if (!craftingTableNearby(BlockPos.containing(holder.position()), level)) {
            return failure("a crafting table is required nearby");
        }
        int width = crafting instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
        int height = crafting instanceof ShapedRecipe shaped ? shaped.getHeight() : 3;
        List<Ingredient> ingredients = crafting.getIngredients();
        if (width > 3 || height > 3 || ingredients.size() > width * height) {
            return failure("only crafting-table recipes are supported");
        }
        int[] assignment = assignIngredients(inventory, ingredients);
        if (assignment == null) {
            return failure("the actor is missing ingredients");
        }
        List<ItemStack> grid = new java.util.ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) {
            int slot = i < assignment.length ? assignment[i] : -1;
            grid.add(slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot).copyWithCount(1));
        }
        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!crafting.matches(input, level)) {
            return failure("the actor is missing ingredients");
        }
        ItemStack crafted = crafting.assemble(input, level.registryAccess());
        if (crafted.isEmpty()) {
            return failure("the recipe produces nothing");
        }
        settle(holder, assignment, crafted, crafting.getRemainingItems(input));
        return new CraftResult(BuiltInRegistries.ITEM.getKey(crafted.getItem()).toString(), crafted.getCount(),
                true, "crafted");
    }

    static void settle(ToolActor holder, int[] assignment, ItemStack crafted, List<ItemStack> leftovers) {
        for (int slot : assignment) {
            if (slot >= 0) {
                holder.inventory().removeItem(slot, 1);
            }
        }
        deliver(holder, crafted);
        for (ItemStack remaining : leftovers) {
            deliver(holder, remaining);
        }
    }

    private static void deliver(ToolActor holder, ItemStack stack) {
        if (!stack.isEmpty()) {
            int placed = Inventories.add(holder.inventory(), stack, MAIN_INVENTORY_SIZE);
            if (placed < stack.getCount()) {
                holder.drop(stack.copyWithCount(stack.getCount() - placed));
            }
        }
    }

    static int[] assignIngredients(Container inventory, List<Ingredient> ingredients) {
        int[] assigned = new int[ingredients.size()];
        java.util.Arrays.fill(assigned, -1);
        int slots = Math.min(MAIN_INVENTORY_SIZE, inventory.getContainerSize());
        for (int i = 0; i < ingredients.size(); i++) {
            if (!ingredients.get(i).isEmpty() && !assign(i, inventory, ingredients, assigned, new boolean[slots])) {
                return null;
            }
        }
        return assigned;
    }

    /** Move an earlier flexible ingredient if it occupies the only slot suitable for this one. */
    private static boolean assign(int ingredient, Container inventory, List<Ingredient> ingredients,
                                  int[] assigned, boolean[] visited) {
        for (int slot = 0; slot < visited.length; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (visited[slot] || stack.isEmpty() || !ingredients.get(ingredient).test(stack)) {
                continue;
            }
            visited[slot] = true;
            int used = 0;
            for (int allocation : assigned) {
                if (allocation == slot) used++;
            }
            if (used < stack.getCount()) {
                assigned[ingredient] = slot;
                return true;
            }
            for (int other = 0; other < assigned.length; other++) {
                if (assigned[other] == slot && assign(other, inventory, ingredients, assigned, visited)) {
                    assigned[ingredient] = slot;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean craftingTableNearby(BlockPos center, ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CRAFTING_TABLE_RADIUS, -2, -CRAFTING_TABLE_RADIUS),
                center.offset(CRAFTING_TABLE_RADIUS, 2, CRAFTING_TABLE_RADIUS))) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private static CraftResult failure(String message) {
        return new CraftResult("", 0, false, message);
    }
}