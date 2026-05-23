package com.rheinmetal.tianshu.platform.provider;

import com.rheinmetal.tianshu.client.language.ClientLanguagePolicy;
import com.rheinmetal.tianshu.provider.IInventoryDataProvider;
import com.rheinmetal.tianshu.snapshot.InventoryItemStackData;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NeoForgeInventoryProvider implements IInventoryDataProvider {

    private List<InventoryItemStackData> cachedInventoryItemStacks = Collections.emptyList();
    private long cachedInventoryItemStacksSignature = Long.MIN_VALUE;

    @Override
    public List<InventoryItemStackData> getInventoryItemStacksData() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            cachedInventoryItemStacks = Collections.emptyList();
            cachedInventoryItemStacksSignature = Long.MIN_VALUE;
            return cachedInventoryItemStacks;
        }

        Inventory inventory = mc.player.getInventory();
        long signature = inventoryIdentitySignature(inventory);
        if (signature == cachedInventoryItemStacksSignature) {
            return cachedInventoryItemStacks;
        }

        List<InventoryItemStackData> result = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String itemId = stack.getItemHolder().getRegisteredName();
            ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
            String displayName = ClientLanguagePolicy.itemDisplayName(stack, key);
            result.add(new InventoryItemStackData(itemId, displayName, stack.getCount(), stack.getMaxStackSize()));
        }

        cachedInventoryItemStacks = Collections.unmodifiableList(result);
        cachedInventoryItemStacksSignature = signature;
        return cachedInventoryItemStacks;
    }

    private long inventoryIdentitySignature(Inventory inventory) {
        long signature = 1125899906842597L;
        int size = inventory != null ? inventory.getContainerSize() : 0;
        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                signature = signature * 31L + i;
                continue;
            }
            signature = signature * 31L + i;
            signature = signature * 31L + stack.getCount();
            signature = signature * 31L + stack.getItemHolder().getRegisteredName().hashCode();
            if (stack.has(DataComponents.CUSTOM_NAME)) {
                signature = signature * 31L + stack.getHoverName().getString().hashCode();
            }
        }
        return signature;
    }
}
