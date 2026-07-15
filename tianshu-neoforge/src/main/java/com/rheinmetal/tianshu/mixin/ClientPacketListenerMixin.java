package com.rheinmetal.tianshu.mixin;

import com.rheinmetal.tianshu.platform.NeoForgePresenceHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleUpdateAdvancementsPacket", at = @At("TAIL"))
    private void tianshu$recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo callbackInfo) {
        NeoForgePresenceHooks.recordAdvancementUpdate(packet);
    }
}
