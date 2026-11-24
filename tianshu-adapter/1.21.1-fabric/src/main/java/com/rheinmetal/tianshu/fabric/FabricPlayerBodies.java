package com.rheinmetal.tianshu.fabric;

import com.mojang.authlib.GameProfile;
import com.rheinmetal.tianshu.mc.body.PlayerBodyFactory;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.util.UUID;

/**
 * Player-backed bodies on Fabric.
 *
 * <p>1.21.1's Fabric API has no {@code FakePlayer}: that class arrived in later lines, so this adapter builds the
 * player itself — a {@link ServerPlayer} with a real (if loopback-only) {@link Connection} attached. That connection is
 * what makes the entity behave like a player instead of crashing the first time something publishes to it: inventory
 * sync, container opening and chat all travel through it.</p>
 *
 * <p>The loopback channel is an {@link EmbeddedChannel}: packets written into it are handled by the connection and
 * dropped, which is exactly what a body needs — no client is on the other end, but every call that dereferences
 * {@code player.connection} finds one.</p>
 */
public final class FabricPlayerBodies implements PlayerBodyFactory {

    @Override
    public ServerPlayer create(ServerLevel level, UUID bodyId) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(bodyId, nameOf(bodyId));
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = loopback();
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    /** A connection that is complete enough for every player-side publish, with nothing on the other end. */
    private static Connection loopback() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // EmbeddedChannel normally retains outbound messages for a test to read. This peer has no reader.
        new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.trySuccess();
            }
        }, connection);
        return connection;
    }

    private static String nameOf(UUID bodyId) {
        return "tianshu_body_" + bodyId.toString().replace("-", "").substring(0, 12);
    }
}