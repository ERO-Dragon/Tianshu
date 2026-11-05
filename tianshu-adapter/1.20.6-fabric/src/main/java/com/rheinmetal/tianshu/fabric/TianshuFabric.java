package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;
import com.rheinmetal.tianshu.protocol.ProtocolWireVersion;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The Fabric entry point, loaded on both sides.
 *
 * <p>Two jobs belong here rather than to the client initializer: answering "is that mod installed?" — which every
 * module asks before it touches an optional partner — and registering the one network channel, which has to be
 * known before any world connects.</p>
 *
 * <p>The optional server component is {@link FabricGameFactCollector} plus {@link FabricServerActionHandler}: it
 * collects this loader's server facts and answers action requests, off by default and operator-only. The action
 * channel is registered on the client too, so a module can request an action and receive the honest
 * {@code FAILED} answer instead of a promise the platform cannot keep.</p>
 *
 * <p>1.20.5 replaced channel-plus-buffer networking with typed payloads: each payload declares a type and a codec,
 * both sides register that pair up front, and a receiver is handed the decoded record. This class registers the
 * payload types and the client-side receivers; the server-side receiver lives in
 * {@link FabricServerActionHandler}.</p>
 */
public final class TianshuFabric implements ModInitializer {

    /** The wire protocol epoch, matching the NeoForge adapter's channel version. */
    public static final String PROTOCOL_VERSION = ProtocolWireVersion.VERSION;

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
        // Payload types first: a receiver may only be registered for a type both registries already know.
        registerPayloads();
        // A dedicated server has no client classes to touch: everything that names one lives in the client branch.
        if (FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
            actionClient = new ServerActionClient(ClientPlayNetworking::send);
            registerClientReceivers();
        }
        // The optional server component: present on every distribution, listens only to server events, and sends
        // only to a client that registered the channel.
        new FabricGameFactCollector().register();
        // The action channel's server half: off by default, one switch per action, operator-only, idempotent.
        FabricServerActionConfig.load(FabricLoader.getInstance().getGameDir());
        FabricServerActionHandler.register();
    }

    /**
     * The payload types Tianshu owns, registered on both sides.
     *
     * <p>The payloads themselves are the shared records; only the registration is Fabric's. This adapter carries no
     * server component of its own, but registering the client-to-server type here is what lets an integrated server
     * answer a request, and what keeps the wire shape identical to the NeoForge adapter's.</p>
     */
    private static void registerPayloads() {
        PayloadTypeRegistry.playC2S().register(ServerActionRequestPacket.TYPE, ServerActionRequestPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ServerActionCatalogPacket.TYPE, ServerActionCatalogPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ServerActionResultPacket.TYPE, ServerActionResultPacket.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(GameFactBatchPacket.TYPE, GameFactBatchPacket.STREAM_CODEC);
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
        ClientPlayNetworking.registerGlobalReceiver(ServerActionResultPacket.TYPE,
                (packet, context) -> actionClient.receiveResult(packet, context.client()::execute));
        ClientPlayNetworking.registerGlobalReceiver(ServerActionCatalogPacket.TYPE,
                (packet, context) -> actionClient.receiveCatalog(packet, context.client()::execute));
    }
}
