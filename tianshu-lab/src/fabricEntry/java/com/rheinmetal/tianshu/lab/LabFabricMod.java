package com.rheinmetal.tianshu.lab;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Both peers must advertise Lab before entering a world which uses its entities. */
public final class LabFabricMod implements ModInitializer {
    record Presence() implements CustomPacketPayload {
        static final Type<Presence> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LabModuleRegistration.MOD_ID, "presence"));
        static final StreamCodec<FriendlyByteBuf, Presence> CODEC = StreamCodec.unit(new Presence());

        @Override
        public Type<Presence> type() {
            return TYPE;
        }
    }

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.configurationS2C().register(Presence.TYPE, Presence.CODEC);
        PayloadTypeRegistry.configurationC2S().register(Presence.TYPE, Presence.CODEC);
        ServerConfigurationNetworking.registerGlobalReceiver(Presence.TYPE, (payload, context) -> {});
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((handler, server) -> {
            if (!ServerConfigurationNetworking.canSend(handler, Presence.TYPE)) {
                handler.disconnect(requiredPeer());
            }
        });
    }

    static Component requiredPeer() {
        return Component.translatable("multiplayer.disconnect.incompatible", LabModuleRegistration.MOD_ID);
    }
}
