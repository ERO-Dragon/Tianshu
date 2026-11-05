package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** The actions the connected server currently allows; sent once when the player joins. */
public record ServerActionCatalogPacket(List<String> enabledActions) implements CustomPacketPayload {

    public static final Type<ServerActionCatalogPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(ModIdentity.MOD_ID, "server_action_catalog"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerActionCatalogPacket> STREAM_CODEC =
            StreamCodec.ofMember(ServerActionCatalogPacket::encode, ServerActionCatalogPacket::decode);

    public ServerActionCatalogPacket {
        enabledActions = enabledActions == null ? List.of() : List.copyOf(enabledActions);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(enabledActions.size());
        for (String action : enabledActions) {
            buffer.writeUtf(action);
        }
    }

    static ServerActionCatalogPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > 16) {
            throw new IllegalArgumentException("invalid action catalog size: " + count);
        }
        List<String> actions = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            actions.add(ServerActionLimits.text(buffer.readUtf(), "action", ServerActionLimits.MAX_KIND_LENGTH));
        }
        return new ServerActionCatalogPacket(actions);
    }
}
