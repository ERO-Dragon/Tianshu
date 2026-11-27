package com.rheinmetal.tianshu.lab;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

@Mod(LabModuleRegistration.MOD_ID)
public final class LabNeoForgeMod {
    private record Presence() implements CustomPacketPayload {
        static final Type<Presence> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(LabModuleRegistration.MOD_ID, "presence"));

        @Override
        public Type<Presence> type() {
            return TYPE;
        }
    }

    public LabNeoForgeMod(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent event) ->
                event.registrar("1").configurationBidirectional(Presence.TYPE,
                        StreamCodec.unit(new Presence()), (payload, context) -> {}));
        // The bench's capabilities exist in every process that loads the bench, including a dedicated server: the
        // tools run on the authoritative side, and a client only ever asks for them over the tool channel.
        com.rheinmetal.tianshu.lab.tool.LabTools.register();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LabModuleRegistration.announce();
        }
    }
}
