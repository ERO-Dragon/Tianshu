package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.RecipeInfo;
import com.rheinmetal.tianshu.api.game.RecipeQuery;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side recipe lookup for 1.21.2–1.21.4. From 1.21.2 the client no longer receives the full recipe
 * manager, but the server still owns it; display() is the public result/ingredient representation.
 */
public final class ServerRecipeQuerySource implements RecipeQueryPort {

    private final MinecraftServer server;

    public ServerRecipeQuerySource(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<List<RecipeInfo>> query(RecipeQuery query) {
        CompletableFuture<List<RecipeInfo>> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(lookup(query));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private List<RecipeInfo> lookup(RecipeQuery query) {
        Identifier output = Identifier.tryParse(query.outputItemId());
        if (output == null) {
            return List.of();
        }
        var level = server.overworld();
        ContextMap context = SlotDisplayContext.fromLevel(level);
        List<RecipeInfo> found = new ArrayList<>();
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            Recipe<?> recipe = holder.value();
            for (RecipeDisplay display : recipe.display()) {
                for (ItemStack stack : display.result().resolveForStacks(context)) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    Identifier produced = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (!output.equals(produced)) {
                        continue;
                    }
                    found.add(new RecipeInfo(holder.id().identifier().toString(), typeId(recipe),
                            inputs(display, context), produced.toString()));
                    if (found.size() >= query.limit()) {
                        return List.copyOf(found);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    private static String typeId(Recipe<?> recipe) {
        Identifier id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return id == null ? "unknown" : id.toString();
    }

    private static List<String> inputs(RecipeDisplay display, ContextMap context) {
        List<SlotDisplay> slots;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            slots = shaped.ingredients();
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            slots = shapeless.ingredients();
        } else if (display instanceof FurnaceRecipeDisplay furnace) {
            slots = List.of(furnace.ingredient());
        } else if (display instanceof StonecutterRecipeDisplay stonecutter) {
            slots = List.of(stonecutter.input());
        } else if (display instanceof SmithingRecipeDisplay smithing) {
            slots = List.of(smithing.template(), smithing.base(), smithing.addition());
        } else {
            slots = List.of();
        }
        List<String> inputs = new ArrayList<>();
        for (SlotDisplay slot : slots) {
            ItemStack stack = slot.resolveForFirstStack(context);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                inputs.add(id.toString());
            }
        }
        return List.copyOf(inputs);
    }
}
