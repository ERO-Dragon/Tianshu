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
import java.util.concurrent.ConcurrentHashMap;

public class NeoForgeRecipeProvider implements IRecipeDataProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_NBT_NODES = 30;

    private final Map<String, List<RecipeData>> recipeCache = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public RecipeTreeData getRecipeTree(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return new RecipeTreeData(itemId, Collections.emptyList());
        }

        List<RecipeData> cached = recipeCache.get(itemId);
        if (cached != null) {
            return new RecipeTreeData(itemId, cached);
        }

        List<RecipeData> matched = new ArrayList<>();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return new RecipeTreeData(itemId, Collections.emptyList());
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
                        if (!itemId.equals(resultItemId)) continue;

                        List<IngredientData> ingredients = extractIngredients(holder);
                        IngredientData result = toItemData(resultStack);

                        String recipeId = holder.id().toString();
                        String recipeType = type.toString();

                        matched.add(new RecipeData(recipeId, recipeType, result, ingredients));
                    } catch (Exception e) {
                        LOGGER.warn("解析配方失败 {}: {}", holder.id(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("遍历配方类型 {} 失败: {}", type, e.getMessage());
            }
        }

        List<RecipeData> frozen = Collections.unmodifiableList(matched);
        recipeCache.put(itemId, frozen);
        LOGGER.debug("按需加载配方: {} -> {} 条", itemId, frozen.size());

        return new RecipeTreeData(itemId, frozen);
    }

    public void invalidateCache() {
        recipeCache.clear();
    }

    public void invalidateCache(String itemId) {
        recipeCache.remove(itemId);
    }

    private List<IngredientData> extractIngredients(RecipeHolder<?> holder) {
        List<IngredientData> ingredients = new ArrayList<>();
        try {
            NonNullList<Ingredient> ingredientList = holder.value().getIngredients();
            for (Ingredient ingredient : ingredientList) {
                if (ingredient.isEmpty()) continue;
                try {
                    ingredients.add(resolveIngredient(ingredient));
                } catch (Exception e) {
                    ingredients.add(new IngredientData("unknown", "未知", 1));
                }
            }
        } catch (Exception e) {
            LOGGER.warn("提取原料列表失败: {}", e.getMessage());
        }
        return ingredients;
    }

    private IngredientData resolveIngredient(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks == null || stacks.length == 0) {
            return new IngredientData("empty", "空", 0);
        }

        List<String> tagItems = new ArrayList<>();
        String tagId = null;

        if (stacks.length > 1) {
            try {
                Set<String> commonTags = null;
                for (ItemStack stack : stacks) {
                    if (stack.isEmpty()) continue;
                    Set<String> itemTags = new HashSet<>();
                    stack.getItemHolder().tags()
                            .map(t -> t.location().toString())
                            .forEach(itemTags::add);
                    if (commonTags == null) {
                        commonTags = itemTags;
                    } else {
                        commonTags.retainAll(itemTags);
                    }
                }

                if (commonTags != null && !commonTags.isEmpty()) {
                    String best = null;
                    for (String tag : commonTags) {
                        if (tag.contains("crafting") || tag.contains("recipe") || tag.equals("minecraft:item_built_in_entity")) continue;
                        if (best == null || tag.length() > best.length()) {
                            best = tag;
                        }
                    }
                    if (best != null) {
                        tagId = "#" + best;
                    }
                }
            } catch (Exception ignored) {}
        }

        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) tagItems.add(stack.getItemHolder().getRegisteredName());
        }

        if (stacks.length == 1) {
            IngredientData data = toItemData(stacks[0]);
            return new IngredientData(data.getItemId(), data.getDisplayName(), data.getCount(),
                    data.getNbtHints(), tagId, tagItems.isEmpty() ? null : tagItems);
        }

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

        return new IngredientData(ids.toString(), names.toString(), count, null,
                tagId, tagItems.isEmpty() ? null : tagItems);
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
