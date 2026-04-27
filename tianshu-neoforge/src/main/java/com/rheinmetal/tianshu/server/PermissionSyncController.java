package com.rheinmetal.tianshu.server;

import com.rheinmetal.tianshu.config.ServerConfig;
import com.rheinmetal.tianshu.network.S2CSyncPermissionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = "tianshu", bus = EventBusSubscriber.Bus.GAME)
public class PermissionSyncController {

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            syncToPlayer(serverPlayer);
        }
    }

    public static void syncToPlayer(ServerPlayer player) {
        // [零信任红线] 唯一权限下发入口：读取 ServerConfig -> 构造 S2C 包 -> 发送
        boolean allowAutoEquip = ServerConfig.ALLOW_AUTO_EQUIP.get();
        boolean allowAutoTrash = ServerConfig.ALLOW_AUTO_TRASH.get();
        boolean allowHighPrecisionMode = ServerConfig.ALLOW_HIGH_PRECISION_MODE.get();
        S2CSyncPermissionPacket packet = new S2CSyncPermissionPacket(allowAutoEquip, allowAutoTrash, allowHighPrecisionMode);
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void syncToAll(net.minecraft.server.MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                syncToPlayer(player);
            }
        }
    }
}
