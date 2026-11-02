package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.ModIdentity;
import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The Fabric entry point, loaded on the client only.
 *
 * <p>Two jobs belong here rather than to the client initializer: answering "is that mod installed?" — which every
 * module asks before it touches an optional partner — and registering the one network channel, which has to be
 * known before any world connects.</p>
 *
 * <p>There is no server component in this adapter yet. The action channel is still registered on the client so a
 * module can request an action and receive the honest {@code FAILED} answer, instead of a promise the platform
 * cannot keep.</p>
 */
public final class TianshuFabric implements ModInitializer {

    /** The wire protocol epoch, matching the NeoForge adapter's channel version. */
    public static final String PROTOCOL_VERSION = "3";

    /** The action channel client: one per process, shared by the network registration and the event layer. */
    private static ServerActionClient actionClient;

    public static ServerActionClient actionClient() {
        return actionClient;
    }

    @Override
    public void onInitialize() {
        // Modules are platform-free: the only sane answer to "is it there?" has to come from here.
        TianshuRuntimePorts.register(InstalledMods.class,
                modId -> modId != null && !modId.isBlank() && FabricLoader.getInstance().isModLoaded(modId));
        // Payload types first: a receiver may only be registered for a type the registry already knows, on both
        // sides. Getting this order wrong fails the server at startup with "no payload type has been registered".
        registerPayloads();
        // A dedicated server has no client classes to touch: everything that names one lives in the client branch.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT) {
            actionClient = new ServerActionClient(ClientPlayNetworking::send);
            registerClientReceivers();
        }
        // The optional server component: present on every distribution, listens only to server events, and sends
        // only to a client that registered the channel.
        new FabricGameFactCollector().register();
        // The action channel's server half: off by default, one switch per action, operator-only, idempotent.
        FabricServerActionConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        FabricServerActionHandler.register();
    }

    /**
     * The one channel Tianshu owns.
     *
     * <p>The payloads themselves are the shared records; only the registration and the thread hop are Fabric's.
     * The server never sends a fact on this side because this adapter carries no server component, but the
     * client-side registration is what lets an integrated server do it once the NeoForge adapter is not involved
     * — and what keeps the wire shape identical when it is.</p>
     */
    private static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(com.rheinmetal.tianshu.mc.network.ServerActionResultPacket.TYPE,
                com.rheinmetal.tianshu.mc.network.ServerActionResultPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket.TYPE,
                com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket.TYPE,
                com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket.STREAM_CODEC);
    }

    /** The client half of the one channel: receiving what the server component (or an integrated server) sends. */
    private static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(GameFactBatchPacket.TYPE, (packet, context) -> {
            var events = packet.toEvents();
            if (events.isEmpty()) {
                return;
            }
            context.client().execute(() -> events.forEach(com.rheinmetal.tianshu.mc.event.WorldEventHooks::publish));
        });
        ClientPlayNetworking.registerGlobalReceiver(
                com.rheinmetal.tianshu.mc.network.ServerActionResultPacket.TYPE,
                (packet, context) -> actionClient.receiveResult(packet, context.client()::execute));
        ClientPlayNetworking.registerGlobalReceiver(
                com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket.TYPE,
                (packet, context) -> actionClient.receiveCatalog(packet, context.client()::execute));
    }


}
