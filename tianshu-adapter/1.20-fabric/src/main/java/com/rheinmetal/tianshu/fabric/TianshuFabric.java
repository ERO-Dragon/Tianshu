package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.assembly.InstalledMods;
import com.rheinmetal.tianshu.api.assembly.TianshuRuntimePorts;
import com.rheinmetal.tianshu.mc.host.ServerActionClient;
import com.rheinmetal.tianshu.mc.network.GameFactBatchPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionCatalogPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionResultPacket;
import com.rheinmetal.tianshu.mc.network.ServerActionRequestPacket;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;

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
 *
 * <p>1.20.1 predates the payload registry: a channel is a {@code ResourceLocation}, each side registers its own
 * receivers, and there is nothing to declare in advance. This class therefore only wires the sender and the
 * client-side receivers; the server-side receiver lives in {@link FabricServerActionHandler}.</p>
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
        // A dedicated server has no client classes to touch: everything that names one lives in the client branch.
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT) {
            actionClient = new ServerActionClient(TianshuFabric::sendToServer);
            registerClientReceivers();
        }
        // The optional server component: present on every distribution, listens only to server events, and sends
        // only to a client that registered the channel.
        new FabricGameFactCollector().register();
        // The action channel's server half: off by default, one switch per action, operator-only, idempotent.
        FabricServerActionConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir());
        FabricServerActionHandler.register();
    }

    /** Encodes one request and hands it to the client's play channel. */
    static void sendToServer(ServerActionRequestPacket payload) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        payload.encode(buffer);
        ClientPlayNetworking.send(ServerActionRequestPacket.CHANNEL, buffer);
    }

    /** The client half of the one channel: receiving what the server component (or an integrated server) sends. */
    private static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(GameFactBatchPacket.CHANNEL,
                (client, handler, buffer, responseSender) -> {
                    GameFactBatchPacket packet = GameFactBatchPacket.decode(buffer);
                    var events = packet.toEvents();
                    if (events.isEmpty()) {
                        return;
                    }
                    client.execute(() ->
                            events.forEach(com.rheinmetal.tianshu.mc.event.WorldEventHooks::publish));
                });
        ClientPlayNetworking.registerGlobalReceiver(ServerActionResultPacket.CHANNEL,
                (client, handler, buffer, responseSender) ->
                        actionClient.receiveResult(ServerActionResultPacket.decode(buffer), client::execute));
        ClientPlayNetworking.registerGlobalReceiver(ServerActionCatalogPacket.CHANNEL,
                (client, handler, buffer, responseSender) ->
                        actionClient.receiveCatalog(ServerActionCatalogPacket.decode(buffer), client::execute));
    }
}
