package com.rheinmetal.tianshu.client.presence;

import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

public final class PresenceClientHooks {
    private static volatile PresenceClientRuntime runtime;

    private PresenceClientHooks() {
    }

    public static void bind(PresenceClientRuntime value) {
        runtime = value;
    }

    public static void clear(PresenceClientRuntime value) {
        if (runtime == value) {
            runtime = null;
        }
    }

    public static void recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet) {
        PresenceClientRuntime current = runtime;
        if (current != null) {
            current.recordAdvancementUpdate(packet);
        }
    }
}
