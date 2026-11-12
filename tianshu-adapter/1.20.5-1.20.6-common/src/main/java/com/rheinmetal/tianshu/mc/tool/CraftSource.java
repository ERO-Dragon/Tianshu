package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.21.1 crafting: consumes one item per ingredient and hands the result to the player.
 *
 * <p>Rules kept deliberately explicit: only crafting-table recipes are accepted, the player must stand near a
 * crafting table, and the whole call is all-or-nothing — if one ingredient is missing, nothing is consumed.</p>
 */
public final class CraftSource implements CraftPort {

    private static final int CRAFTING_TABLE_RADIUS = 4;
    private static final int MAIN_INVENTORY_SIZE = 36;

    private final MinecraftServer server;

    public CraftSource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<CraftResult> craft(String playerId, CraftRequest request) {
        CompletableFuture<CraftResult> result = new CompletableFuture<>();
        server.execute(() -> result.complete(craftNow(playerId, request)));
        return result;
    }

    private CraftResult craftNow(String playerId, CraftRequest request) {
        ServerPlayer player = player(playerId);
        if (player == null) {
            return failure("the player is not online");
        }
        ServerLevel level = player.serverLevel();
        ResourceLocation id = ResourceLocation.tryParse(request.recipeId());
        if (id == null) {
            return failure("that is not a recipe id");
        }
        Optional<RecipeHolder<?>> found = level.getRecipeManager().byKey(id);
        if (found.isEmpty()) {
            return failure("no such recipe");
        }
        Recipe<?> recipe = found.get().value();
        if (!(recipe instanceof CraftingRecipe)) {
            return failure("only crafting-table recipes are supported");
        }
        if (!craftingTableNearby(player, level)) {
            return failure("a crafting table is required nearby");
        }
        ItemStack produced = recipe.getResultItem(level.registryAccess());
        if (produced.isEmpty()) {
            return failure("the recipe produces nothing");
        }
        Inventory inventory = player.getInventory();
        int[] assignment = assignIngredients(inventory, recipe.getIngredients());
        if (assignment == null) {
            return failure("the player is missing ingredients");
        }
        for (int slot : assignment) {
            inventory.removeItem(slot, 1);
        }
        ItemStack crafted = produced.copy();
        crafted.setCount(1);
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(crafted.getItem());
        String name = itemId == null ? "unknown" : itemId.toString();
        if (!inventory.add(crafted)) {
            player.drop(crafted, false);
        }
        return new CraftResult(name, 1, true, "crafted");
    }

    private static int[] assignIngredients(Inventory inventory, List<Ingredient> ingredients) {
        int[] assignment = new int[ingredients.size()];
        boolean[] used = new boolean[MAIN_INVENTORY_SIZE];
        for (int index = 0; index < ingredients.size(); index++) {
            Ingredient ingredient = ingredients.get(index);
            if (ingredient.isEmpty()) {
                assignment[index] = -1;
                continue;
            }
            int slot = -1;
            for (int candidate = 0; candidate < MAIN_INVENTORY_SIZE; candidate++) {
                if (used[candidate]) {
                    continue;
                }
                ItemStack stack = inventory.getItem(candidate);
                if (!stack.isEmpty() && ingredient.test(stack)) {
                    slot = candidate;
                    break;
                }
            }
            if (slot < 0) {
                return null;
            }
            used[slot] = true;
            assignment[index] = slot;
        }
        return assignment;
    }

    private static boolean craftingTableNearby(ServerPlayer player, ServerLevel level) {
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CRAFTING_TABLE_RADIUS, -2, -CRAFTING_TABLE_RADIUS),
                center.offset(CRAFTING_TABLE_RADIUS, 2, CRAFTING_TABLE_RADIUS))) {
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private ServerPlayer player(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(playerId.trim()));
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static CraftResult failure(String message) {
        return new CraftResult("", 0, false, message);
    }
}