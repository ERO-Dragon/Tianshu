package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.client.ClientNetworkHandler;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = "tianshu", bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        // [零信任红线] 仅注册 S2C 方向，严禁出现任何 C2S 注册
        registrar.playToClient(
                S2CSyncPermissionPacket.TYPE,
                S2CSyncPermissionPacket.STREAM_CODEC,
                ClientNetworkHandler::handleSyncPermission
        );
    }
}
