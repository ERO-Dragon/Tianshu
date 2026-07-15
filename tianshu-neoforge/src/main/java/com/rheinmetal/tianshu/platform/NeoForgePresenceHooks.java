package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

public final class NeoForgePresenceHooks {
    private static volatile Binding binding;

    private NeoForgePresenceHooks() {
    }

    public static void bind(PresenceClientRuntime runtime) {
        binding = runtime == null ? null : new Binding(runtime, new NeoForgePresenceAdvancementTracker());
    }

    public static void clear(PresenceClientRuntime runtime) {
        Binding current = binding;
        if (current != null && current.runtime() == runtime) {
            binding = null;
        }
    }

    public static void recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet) {
        Binding current = binding;
        if (current != null) {
            current.runtime().recordWorldEvents(current.advancementTracker().collect(packet));
        }
    }

    private record Binding(
            PresenceClientRuntime runtime,
            NeoForgePresenceAdvancementTracker advancementTracker
    ) {
    }
}
