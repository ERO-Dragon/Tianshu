package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

import java.util.concurrent.atomic.AtomicReference;

public final class NeoForgePresenceHooks {
    private static final AtomicReference<Binding> binding = new AtomicReference<>();

    private NeoForgePresenceHooks() {
    }

    public static void bind(PresenceClientRuntime runtime) {
        binding.set(runtime == null ? null : new Binding(runtime, new NeoForgePresenceAdvancementTracker()));
    }

    public static void clear(PresenceClientRuntime runtime) {
        Binding current = binding.get();
        if (current != null && current.runtime() == runtime) {
            binding.compareAndSet(current, null);
        }
    }

    public static void resetWorldSession(PresenceClientRuntime runtime) {
        Binding current = binding.get();
        if (current != null && current.runtime() == runtime) {
            current.advancementTracker().reset();
        }
    }

    public static void recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet) {
        Binding current = binding.get();
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
