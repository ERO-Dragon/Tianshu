package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.CraftPort;
import com.rheinmetal.tianshu.api.game.CraftRequest;
import com.rheinmetal.tianshu.api.game.CraftResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Crafting for 1.21.2+: the recipe object no longer exposes result/ingredients, so the display representation is
 * the public source. One ingredient per slot is consumed, and the whole call remains all-or-nothing.
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
        Optional<RecipeHolder<?>> found = server.getRecipeManager()
                .byKey(ResourceKey.create(Registries.RECIPE, id));
        if (found.isEmpty()) {
            return failure("no such recipe");
        }
        Recipe<?> recipe = found.get().value();
        RecipeDisplay display = firstCraftingDisplay(recipe);
        if (display == null) {
            return failure("only crafting-table recipes are supported");
        }
        if (!craftingTableNearby(player, level)) {
            return failure("a crafting table is required nearby");
        }
        ContextMap context = SlotDisplayContext.fromLevel(level);
        ItemStack produced = display.result().resolveForFirstStack(context);
        if (produced.isEmpty()) {
            return failure("the recipe produces nothing");
        }
        List<List<ItemStack>> needed = ingredients(display, context);
        Inventory inventory = player.getInventory();
        int[] assignment = assignIngredients(inventory, needed);
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

    private static RecipeDisplay firstCraftingDisplay(Recipe<?> recipe) {
        for (RecipeDisplay display : recipe.display()) {
            if (display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay) {
                return display;
            }
        }
        return null;
    }

    private static List<List<ItemStack>> ingredients(RecipeDisplay display, ContextMap context) {
        List<SlotDisplay> slots;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            slots = shaped.ingredients();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            slots = shapeless.ingredients();
        } else {
            return List.of();
        }
        List<List<ItemStack>> needed = new ArrayList<>();
        for (SlotDisplay slot : slots) {
            List<ItemStack> options = new ArrayList<>();
            for (ItemStack stack : slot.resolveForStacks(context)) {
                if (!stack.isEmpty()) {
                    options.add(stack.copyWithCount(1));
                }
            }
            if (!options.isEmpty()) {
                needed.add(List.copyOf(options));
            }
        }
        return List.copyOf(needed);
    }

    private static int[] assignIngredients(Inventory inventory, List<List<ItemStack>> ingredients) {
        int[] assignment = new int[ingredients.size()];
        int[] used = new int[MAIN_INVENTORY_SIZE];
        for (int index = 0; index < ingredients.size(); index++) {
            int slot = -1;
            for (int candidate = 0; candidate < MAIN_INVENTORY_SIZE && slot < 0; candidate++) {
                ItemStack stack = inventory.getItem(candidate);
                if (stack.isEmpty() || used[candidate] >= stack.getCount()) {
                    continue;
                }
                for (ItemStack option : ingredients.get(index)) {
                    if (ItemStack.isSameItemSameComponents(stack, option)) {
                        slot = candidate;
                        break;
                    }
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
