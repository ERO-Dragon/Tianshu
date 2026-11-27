package com.rheinmetal.tianshu.lab;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Advertises Lab on Fabric so a peer can tell whether the other side has the bench.
 *
 * <p>It used to disconnect a peer that had not announced the channel before configuration finished. The announcement
 * is not available at that moment — a client registers the payload during mod initialisation and Fabric reports it
 * later — so the check rejected clients that do have the bench. A development bench is not a gate on entering the
 * world: the acceptance cases that need the other side already report "no authoritative side" and skip, so the
 * channel is registered and answered best-effort.</p>
 */
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
        // Same reasoning as the NeoForge entry: the bench's capabilities belong to whichever process runs the
        // authoritative side, and the client reaches them through the tool channel.
        com.rheinmetal.tianshu.lab.tool.LabTools.register();
        PayloadTypeRegistry.configurationS2C().register(Presence.TYPE, Presence.CODEC);
        PayloadTypeRegistry.configurationC2S().register(Presence.TYPE, Presence.CODEC);
        ServerConfigurationNetworking.registerGlobalReceiver(Presence.TYPE, (payload, context) -> {});
    }

    static Component requiredPeer() {
        return Component.translatable("multiplayer.disconnect.incompatible", LabModuleRegistration.MOD_ID);
    }
}
