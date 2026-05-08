package com.rheinmetal.tianshu.network;

import com.rheinmetal.tianshu.client.ClientNetworkHandler;
import com.rheinmetal.tianshu.server.JunkClearServerHandler;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = "tianshu", bus = EventBusSubscriber.Bus.MOD)
public class NetworkHandler {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                S2CSyncPermissionPacket.TYPE,
                S2CSyncPermissionPacket.STREAM_CODEC,
                ClientNetworkHandler::handleSyncPermission
        );
        registrar.playToClient(
                S2CLockAlertPacket.TYPE,
                S2CLockAlertPacket.STREAM_CODEC,
                ClientNetworkHandler::handleLockAlert
        );
        registrar.playToClient(
                S2CJunkClearResultPacket.TYPE,
                S2CJunkClearResultPacket.STREAM_CODEC,
                ClientNetworkHandler::handleJunkClearResult
        );
        registrar.playToServer(
                C2SRequestClearJunkPacket.TYPE,
                C2SRequestClearJunkPacket.STREAM_CODEC,
                JunkClearServerHandler::handle
        );
    }
}
