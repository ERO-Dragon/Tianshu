package com.rheinmetal.tianshu.mc.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sends one payload to the server; the loader decides how, the channel does not care. */
@FunctionalInterface
public interface PayloadSender {

    void toServer(CustomPacketPayload payload);
}
