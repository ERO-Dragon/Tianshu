package com.rheinmetal.tianshu.lab;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;

/**
 * The client half of the bench advertisement.
 *
 * <p>It used to disconnect itself when the server had not advertised {@code tianshu_lab:presence} by the end of
 * configuration. That turned a development bench into a hard gate on joining a server — the same mistake the NeoForge
 * entry made on the server side, and the reason a single-sided install could not be tested. The receiver is still
 * registered so a server that does advertise it is answered; the cases that need the other side report what they are
 * missing and skip.</p>
 */
public final class LabFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        LabModuleRegistration.announce();
        ClientConfigurationNetworking.registerGlobalReceiver(LabFabricMod.Presence.TYPE, (payload, context) -> {});
    }
}
