package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.core.IRBaseUtils;
import com.rheinmetal.tianshu.function.ir.core.ItemContextProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.Set;

final class ClientItemContextCollector implements ItemContextProvider {
    @Override
    public Set<Integer> getContextInternalIds() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return Set.of();
        }

        HashSet<Integer> contextIds = new HashSet<>(64);
        addStack(minecraft.player.getMainHandItem(), contextIds);
        addStack(minecraft.player.getOffhandItem(), contextIds);

        Inventory inventory = minecraft.player.getInventory();
        addStacks(inventory.items, contextIds);
        addStacks(inventory.armor, contextIds);
        addStacks(inventory.offhand, contextIds);

        AbstractContainerMenu menu = minecraft.player.containerMenu;
        if (menu != null) {
            for (Slot slot : menu.slots) {
                addStack(slot.getItem(), contextIds);
            }
        }

        return contextIds;
    }

    private void addStacks(Iterable<ItemStack> stacks, Set<Integer> contextIds) {
        for (ItemStack stack : stacks) {
            addStack(stack, contextIds);
        }
    }

    private void addStack(ItemStack stack, Set<Integer> contextIds) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemKey == null) {
            return;
        }
        Integer internalId = IRBaseUtils.forwardLookupMap.get(itemKey.toString());
        if (internalId != null) {
            contextIds.add(internalId);
        }
    }
}
