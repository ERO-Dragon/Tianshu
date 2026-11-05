package com.rheinmetal.tianshu.mc.network;

import com.rheinmetal.tianshu.api.ModIdentity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** The actions the connected server currently allows; sent once when the player joins. */
public record ServerActionCatalogPacket(List<String> enabledActions) {

    /** The channel this packet travels on; both loaders register the same id so the wire shape stays identical. */
    public static final ResourceLocation CHANNEL =
            new ResourceLocation(ModIdentity.MOD_ID, "server_action_catalog");

    public ServerActionCatalogPacket {
        enabledActions = enabledActions == null ? List.of() : List.copyOf(enabledActions);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(enabledActions.size());
        for (String action : enabledActions) {
            buffer.writeUtf(action);
        }
    }

    public static ServerActionCatalogPacket decode(FriendlyByteBuf buffer) {
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
