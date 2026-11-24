package com.rheinmetal.tianshu.lab;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;

public final class LabFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LabModuleRegistration.announce();
        ClientConfigurationNetworking.registerGlobalReceiver(LabFabricMod.Presence.TYPE, (payload, context) -> {});
        ClientConfigurationConnectionEvents.COMPLETE.register((handler, client) -> {
            if (!ClientConfigurationNetworking.canSend(LabFabricMod.Presence.TYPE)) {
                ClientConfigurationNetworking.getSender().disconnect(LabFabricMod.requiredPeer());
            }
        });
    }
}
