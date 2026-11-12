package com.rheinmetal.tianshu.mc.tool;

import com.rheinmetal.tianshu.api.game.InventoryEntry;
import com.rheinmetal.tianshu.api.game.InventoryQueryPort;
import com.rheinmetal.tianshu.api.game.InventorySnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The local player's inventory, read on the client thread. Other players' inventories are server state and are
 * deliberately not readable here.
 */
public final class InventoryQuerySource implements InventoryQueryPort {

    @Override
    public CompletableFuture<InventorySnapshot> read() {
        CompletableFuture<InventorySnapshot> result = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            try {
                result.complete(lookup());
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private static InventorySnapshot lookup() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return new InventorySnapshot(List.of());
        }
        Inventory inventory = player.getInventory();
        List<InventoryEntry> entries = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null) {
                entries.add(new InventoryEntry(slot, id.toString(), stack.getCount()));
            }
        }
        return new InventorySnapshot(List.copyOf(entries));
    }
}
