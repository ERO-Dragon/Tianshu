package com.rheinmetal.tianshu.server;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.config.ServerConfig;
import com.rheinmetal.tianshu.function.junk.JunkItemIdPolicy;
import com.rheinmetal.tianshu.network.C2SRequestClearJunkPacket;
import com.rheinmetal.tianshu.network.S2CJunkClearResultPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

public final class JunkClearServerHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private JunkClearServerHandler() {
    }

    public static void handle(C2SRequestClearJunkPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!ServerConfig.ALLOW_AUTO_TRASH.get()) {
                sendResult(player, false, 0, 0, "服务器不支持自动清理");
                return;
            }
            ClearResult result = clear(player, packet.itemIds());
            sendResult(player, true, result.stackCount(), result.itemCount(), "已丢弃 " + result.itemCount() + " 个物品");
            LOGGER.info("[净囊] player={} stacks={} items={}", player.getGameProfile().getName(), result.stackCount(), result.itemCount());
        });
    }

    private static ClearResult clear(ServerPlayer player, java.util.List<String> requestedItemIds) {
        Set<String> requested = new HashSet<>();
        for (String itemId : requestedItemIds) {
            if (JunkItemIdPolicy.canPersistAsJunk(itemId)) {
                requested.add(itemId);
            }
        }
        if (requested.isEmpty()) return new ClearResult(0, 0);
        Inventory inventory = player.getInventory();
        int itemCount = 0;
        int stackCount = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            String itemId = stack.getItemHolder().getRegisteredName();
            if (!requested.contains(itemId)) continue;
            ItemStack dropped = stack.copy();
            inventory.setItem(i, ItemStack.EMPTY);
            player.drop(dropped, false, true);
            itemCount += dropped.getCount();
            stackCount++;
        }
        inventory.setChanged();
        return new ClearResult(stackCount, itemCount);
    }

    private static void sendResult(ServerPlayer player, boolean success, int stackCount, int itemCount, String message) {
        PacketDistributor.sendToPlayer(player, new S2CJunkClearResultPacket(success, stackCount, itemCount, message));
    }

    private record ClearResult(int stackCount, int itemCount) {
    }
}
