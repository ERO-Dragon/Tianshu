package com.rheinmetal.tianshu.platform.provider;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.provider.IRecipeDataProvider;
import com.rheinmetal.tianshu.snapshot.IngredientData;
import com.rheinmetal.tianshu.snapshot.RecipeData;
import com.rheinmetal.tianshu.snapshot.RecipeTreeData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.*;

public class NeoForgeRecipeProvider implements IRecipeDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 30;

    private volatile Map<String, List<RecipeData>> recipeCache;

    @Override
    public RecipeTreeData getRecipeTree(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return new RecipeTreeData(itemId, Collections.emptyList());
        }

        Map<String, List<RecipeData>> cache = getOrBuildCache();
        List<RecipeData> matched = cache.getOrDefault(itemId, Collections.emptyList());
        return new RecipeTreeData(itemId, matched);
    }

    private Map<String, List<RecipeData>> getOrBuildCache() {
        if (recipeCache != null) {
            return recipeCache;
        }
        synchronized (this) {
            if (recipeCache != null) {
                return recipeCache;
            }
            Map<String, List<RecipeData>> built = buildRecipeCache();
            recipeCache = built;
            LOGGER.info("配方缓存构建完成，共 {} 个物品条目", built.size());
            return recipeCache;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, List<RecipeData>> buildRecipeCache() {
        Map<String, List<RecipeData>> cache = new LinkedHashMap<>();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return cache;
        }

        Level level = mc.level;
        RecipeManager recipeManager = level.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();

        for (RecipeType<?> type : BuiltInRegistries.RECIPE_TYPE) {
            try {
                List<? extends RecipeHolder<?>> recipes = recipeManager.getAllRecipesFor((RecipeType) type);
                if (recipes == null || recipes.isEmpty()) continue;

                for (RecipeHolder<?> holder : recipes) {
                    try {
                        ItemStack resultStack = holder.value().getResultItem(registries);
                        if (resultStack.isEmpty()) continue;

                        String resultItemId = resultStack.getItemHolder().getRegisteredName();

                        List<IngredientData> ingredients = extractIngredients(holder);
                        IngredientData result = toItemData(resultStack);

                        String recipeId = holder.id().toString();
                        String recipeType = type.toString();

                        RecipeData recipeData = new RecipeData(recipeId, recipeType, result, ingredients);

                        cache.computeIfAbsent(resultItemId, k -> new ArrayList<>()).add(recipeData);
                    } catch (Exception e) {
                        LOGGER.warn("解析配方失败 {}: {}", holder.id(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("遍历配方类型 {} 失败: {}", type, e.getMessage());
            }
        }

        Map<String, List<RecipeData>> frozen = new LinkedHashMap<>(cache.size());
        for (Map.Entry<String, List<RecipeData>> entry : cache.entrySet()) {
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(frozen);
    }

    public void invalidateCache() {
        recipeCache = null;
    }

    private List<IngredientData> extractIngredients(RecipeHolder<?> holder) {
        List<IngredientData> ingredients = new ArrayList<>();
        try {
            NonNullList<Ingredient> ingredientList = holder.value().getIngredients();
            for (Ingredient ingredient : ingredientList) {
                if (ingredient.isEmpty()) continue;
                try {
                    ItemStack[] stacks = ingredient.getItems();
                    if (stacks == null || stacks.length == 0) continue;

                    if (stacks.length == 1) {
                        ingredients.add(toItemData(stacks[0]));
                    } else {
                        StringBuilder names = new StringBuilder();
                        StringBuilder ids = new StringBuilder();
                        int count = 1;
                        for (ItemStack stack : stacks) {
                            if (stack.isEmpty()) continue;
                            if (names.length() > 0) names.append("/");
                            names.append(LocalizationHelper.safeGetDisplayName(stack.getHoverName().getString()));
                            if (ids.length() > 0) ids.append("/");
                            ids.append(stack.getItemHolder().getRegisteredName());
                            count = stack.getCount();
                        }
                        ingredients.add(new IngredientData(ids.toString(), names.toString(), count));
                    }
                } catch (Exception e) {
                    ingredients.add(new IngredientData("unknown", "未知", 1));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("提取原料列表失败: {}", e.getMessage());
        }
        return ingredients;
    }

    private IngredientData toItemData(ItemStack stack) {
        if (stack.isEmpty()) {
            return new IngredientData("empty", "空", 0);
        }
        String itemId = stack.getItemHolder().getRegisteredName();
        String displayName = LocalizationHelper.safeGetDisplayName(stack.getHoverName().getString());
        int count = stack.getCount();
        Map<String, String> nbtHints = extractNbtHints(stack);
        return new IngredientData(itemId, displayName, count, nbtHints);
    }

    private Map<String, String> extractNbtHints(ItemStack stack) {
        Map<String, String> hints = new LinkedHashMap<>();
        try {
            CustomData customData = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
            if (customData != null && !customData.isEmpty()) {
                flattenTag(customData.copyTag(), "custom", hints);
            }
        } catch (Exception e) {
            LOGGER.warn("提取配方原料 NBT 失败: {}", e.getMessage());
        }
        return hints;
    }

    private void flattenTag(Tag tag, String prefix, Map<String, String> output) {
        if (output.size() >= MAX_NBT_NODES) {
            if (!output.containsKey("_truncated")) {
                output.put("_truncated", "true");
            }
            return;
        }
        try {
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    if (output.size() >= MAX_NBT_NODES) {
                        output.put("_truncated", "true");
                        return;
                    }
                    String childPath = prefix + "." + key;
                    Tag child = compound.get(key);
                    if (child != null) {
                        flattenTag(child, childPath, output);
                    }
                }
            } else if (tag instanceof CollectionTag<?> collection) {
                output.put(prefix, "[list:" + collection.size() + "]");
            } else {
                output.put(prefix, tag.toString());
            }
        } catch (Exception e) {
            output.put(prefix, "[error]");
        }
    }
}
