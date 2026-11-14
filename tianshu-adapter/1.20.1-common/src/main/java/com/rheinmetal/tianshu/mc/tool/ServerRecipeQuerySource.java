package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.RecipeInfo;
import com.rheinmetal.tianshu.api.game.RecipeQuery;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Server-side recipe lookup for the legacy recipe manager (1.20.1–1.20.4). */
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
        ResourceLocation output = ResourceLocation.tryParse(query.outputItemId());
        if (output == null) {
            return List.of();
        }
        var level = server.overworld();
        List<RecipeInfo> found = new ArrayList<>();
        for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) {
                continue;
            }
            ResourceLocation produced = BuiltInRegistries.ITEM.getKey(result.getItem());
            if (!output.equals(produced)) {
                continue;
            }
            found.add(new RecipeInfo(recipe.getId().toString(), typeId(recipe), inputs(recipe),
                    produced.toString()));
            if (found.size() >= query.limit()) {
                break;
            }
        }
        return List.copyOf(found);
    }

    private static String typeId(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return id == null ? "unknown" : id.toString();
    }

    private static List<String> inputs(Recipe<?> recipe) {
        List<String> inputs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] choices = ingredient.getItems();
            if (choices.length == 0 || choices[0].isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(choices[0].getItem());
            if (id != null) {
                inputs.add(id.toString());
            }
        }
        return List.copyOf(inputs);
    }
}
