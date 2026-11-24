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
        CompletableFuture<CraftResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(craftNow(actor, request)));
        return result;
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
        ItemStack produced = crafting.getResultItem(level.registryAccess());
        if (produced.isEmpty()) {
            return failure("the recipe produces nothing");
        }
        int[] assignment = assignIngredients(inventory, crafting.getIngredients());
        if (assignment == null) {
            return failure("the actor is missing ingredients");
        }
        for (int slot : assignment) {
            inventory.removeItem(slot, 1);
        }
        // The recipe decides the count: a crafting-table recipe yields four sticks, and forcing one silently
        // short-changed the player on every batch recipe. Remaining items (a bucket from cake, say) are the recipe's
        // own leftovers and go back to the actor with the product.
        ItemStack crafted = produced.copy();
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(crafted.getItem());
        String name = itemId == null ? "unknown" : itemId.toString();
        int craftedCount = crafted.getCount();
        if (Inventories.add(inventory, crafted, MAIN_INVENTORY_SIZE) <= 0) {
            holder.drop(crafted);
        }
        int side = assignment.length <= 4 ? 2 : 3;
        net.minecraft.world.item.crafting.CraftingInput input =
                net.minecraft.world.item.crafting.CraftingInput.of(side, side, craftingGrid(assignment, inventory));
        for (ItemStack remaining : crafting.getRemainingItems(input)) {
            if (!remaining.isEmpty()) {
                if (Inventories.add(inventory, remaining.copy(), MAIN_INVENTORY_SIZE) <= 0) {
                    holder.drop(remaining.copy());
                }
            }
        }
        return new CraftResult(name, craftedCount, true, "crafted");
    }

    /** The recipe's view of the grid: one item per ingredient slot, in the order the assignment recorded. */
    /** The recipe's view of the grid: one item per ingredient slot, in the order the assignment recorded. */
    private static java.util.List<ItemStack> craftingGrid(int[] assignment, Container inventory) {
        java.util.List<ItemStack> grid = new java.util.ArrayList<>();
        for (int index = 0; index < Math.max(1, assignment.length); index++) {
            int slot = index < assignment.length ? assignment[index] : -1;
            grid.add(slot >= 0 ? inventory.getItem(slot).copyWithCount(1) : ItemStack.EMPTY);
        }
        return grid;
    }

    private static int[] assignIngredients(Container inventory, List<Ingredient> ingredients) {
        int[] assignment = new int[ingredients.size()];
        int[] used = new int[MAIN_INVENTORY_SIZE];
        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient ingredient = ingredients.get(index);
            if (ingredient.isEmpty()) {
                assignment[index] = -1;
                continue;
            }
            int slot = -1;
            for (int candidate = 0; candidate < MAIN_INVENTORY_SIZE; candidate++) {
                ItemStack stack = inventory.getItem(candidate);
                if (stack.isEmpty() || used[candidate] >= stack.getCount()) {
                    continue;
                }
                if (ingredient.test(stack)) {
                    slot = candidate;
                    break;
                }
            }
            if (slot < 0) {
                return null;
            }
            used[slot]++;
            assignment[index] = slot;
        }
        return assignment;
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