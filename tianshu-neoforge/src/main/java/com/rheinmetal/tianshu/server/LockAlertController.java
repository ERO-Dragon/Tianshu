package com.rheinmetal.tianshu.server;

import com.rheinmetal.tianshu.config.ServerConfig;
import com.rheinmetal.tianshu.network.S2CLockAlertPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [服务端] 锁定检测与定向推送控制器
 *
 * 核心设计：
 * 1. 每 Tick 扫描所有维度中的玩家。
 * 2. 对每个玩家，检查其周围 16 格内的敌对 Mob 是否将其设为目标。
 * 3. 仅当 ServerConfig.ALLOW_HIGH_PRECISION_MODE == true 时才执行扫描。
 * 4. 只将锁定该玩家的实体 UUID 列表，通过 S2C 包发送给该玩家本人。
 * 5. 不广播，不发送给其他玩家。
 */
@EventBusSubscriber(modid = "tianshu", bus = EventBusSubscriber.Bus.GAME)
public class LockAlertController {

    // 扫描半径（与雷达警戒范围一致）
    private static final double SCAN_RADIUS = 16.0;
    // 发送冷却：同一玩家连续发送间隔（Tick），防止网络洪泛
    private static final int SEND_COOLDOWN_TICKS = 5;

    // 玩家 UUID -> 下次允许发送的 Tick 时间
    private static final Map<UUID, Long> playerSendCooldowns = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // [零信任] 如果服务端未开启高精度模式，直接跳过所有扫描
        if (!ServerConfig.ALLOW_HIGH_PRECISION_MODE.get()) {
            return;
        }

        long currentTick = event.getServer().getTickCount();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                // 冷却检查
                Long nextAllowed = playerSendCooldowns.get(player.getUUID());
                if (nextAllowed != null && currentTick < nextAllowed) {
                    continue;
                }

                List<UUID> lockedUuids = scanLockedEntitiesForPlayer(player, level);

                // 无论是否有锁定实体，都发送（空列表表示"当前无威胁"，用于清除客户端状态）
                S2CLockAlertPacket packet = new S2CLockAlertPacket(lockedUuids);
                PacketDistributor.sendToPlayer(player, packet);

                playerSendCooldowns.put(player.getUUID(), currentTick + SEND_COOLDOWN_TICKS);
            }
        }
    }

    /**
     * 扫描指定玩家周围敌对实体，返回锁定该玩家的实体 UUID 列表
     */
    private static List<UUID> scanLockedEntitiesForPlayer(ServerPlayer player, ServerLevel level) {
        List<UUID> result = new ArrayList<>();

        AABB scanBox = new AABB(
                player.getX() - SCAN_RADIUS, player.getY() - SCAN_RADIUS, player.getZ() - SCAN_RADIUS,
                player.getX() + SCAN_RADIUS, player.getY() + SCAN_RADIUS, player.getZ() + SCAN_RADIUS
        );

        for (LivingEntity living : level.getEntitiesOfClass(LivingEntity.class, scanBox)) {
            if (!(living instanceof Mob mob)) continue;
            if (!isHostile(mob)) continue;

            LivingEntity target = mob.getTarget();
            if (target != null && target.getUUID().equals(player.getUUID())) {
                result.add(mob.getUUID());
            }
        }

        return result;
    }

    /**
     * 判断 Mob 是否为敌对实体
     * 使用 Minecraft 原生 Enemy 接口判定，覆盖所有敌对生物（包括 Mod 新增）
     */
    private static boolean isHostile(Mob mob) {
        return mob instanceof net.minecraft.world.entity.monster.Enemy;
    }
}
