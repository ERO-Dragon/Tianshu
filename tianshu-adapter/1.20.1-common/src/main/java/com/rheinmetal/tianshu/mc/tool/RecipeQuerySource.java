package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.RecipeInfo;
import com.rheinmetal.tianshu.api.game.RecipeQuery;
import com.rheinmetal.tianshu.api.game.RecipeQueryPort;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The 1.20.x recipe source behind {@code tianshu:recipe.query}: the client already receives every recipe, so the
 * lookup is local and needs no server component.
 *
 * <p>This generation has no {@code RecipeHolder}; the recipe carries its own id. The port hands the work to the
 * client thread because the recipe manager belongs to the level, and never blocks the caller.</p>
 */
public final class RecipeQuerySource implements RecipeQueryPort {

    @Override
    public CompletableFuture<List<RecipeInfo>> query(RecipeQuery query) {
        CompletableFuture<List<RecipeInfo>> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            try {
                result.complete(lookup(minecraft, query));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static List<RecipeInfo> lookup(Minecraft minecraft, RecipeQuery query) {
        Level level = minecraft.level;
        if (level == null) {
            return List.of();
        }
        ResourceLocation output = ResourceLocation.tryParse(query.outputItemId());
        if (output == null) {
            return List.of();
        }
        List<RecipeInfo> found = new ArrayList<>();
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
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
