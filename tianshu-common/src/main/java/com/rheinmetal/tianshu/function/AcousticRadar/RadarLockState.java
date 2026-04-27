package com.rheinmetal.tianshu.function.AcousticRadar;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [客户端] 服务端锁定状态缓存
 *
 * 设计约束：
 * 1. 线程安全：所有操作在 ConcurrentHashMap 上执行。
 * 2. 极简设计：只存储服务端确认的锁定实体 UUID。
 * 3. 被动更新：仅由 S2C 包驱动，不主动计算。
 */
public final class RadarLockState {

    private RadarLockState() {}

    // 服务端最近一次发送的、锁定本地玩家的实体 UUID 集合
    private static final Set<UUID> serverLockedUuids = ConcurrentHashMap.newKeySet();

    /**
     * 由 ClientNetworkHandler 调用，更新服务端锁定状态
     */
    public static void updateFromServer(java.util.List<UUID> uuids) {
        serverLockedUuids.clear();
        if (uuids != null) {
            serverLockedUuids.addAll(uuids);
        }
    }

    /**
     * 查询某个实体 UUID 是否被服务端确认为锁定本地玩家
     */
    public static boolean isLockedByServer(UUID entityUuid) {
        return serverLockedUuids.contains(entityUuid);
    }

    /**
     * 获取当前所有服务端确认的锁定实体 UUID（只读视图）
     */
    public static Set<UUID> getServerLockedUuids() {
        return Collections.unmodifiableSet(serverLockedUuids);
    }

    /**
     * 清空状态（例如玩家退出世界时）
     */
    public static void clear() {
        serverLockedUuids.clear();
    }
}
