package com.rheinmetal.tianshu.client;

import com.rheinmetal.tianshu.core.FeatureManager;
import com.rheinmetal.tianshu.function.AcousticRadar.RadarLockState;
import com.rheinmetal.tianshu.network.S2CLockAlertPacket;
import com.rheinmetal.tianshu.network.S2CSyncPermissionPacket;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientNetworkHandler {

    // [零信任红线] 客户端唯一真相来源：仅接受 S2C 包写入 FeatureManager
    public static void handleSyncPermission(S2CSyncPermissionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            FeatureManager.setAutoEquip(packet.allowAutoEquip());
            FeatureManager.setAutoTrash(packet.allowAutoTrash());
            FeatureManager.setHighPrecisionMode(packet.allowHighPrecisionMode());
        });
    }

    /**
     * 处理服务端发来的定向锁定信息包
     */
    public static void handleLockAlert(S2CLockAlertPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            RadarLockState.updateFromServer(packet.lockedEntityUuids());
        });
    }
}
