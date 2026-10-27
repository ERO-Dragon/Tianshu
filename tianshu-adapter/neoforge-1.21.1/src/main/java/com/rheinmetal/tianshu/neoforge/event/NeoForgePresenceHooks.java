package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.client.presence.PresenceClientRuntime;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class NeoForgePresenceHooks {
    private static final AtomicReference<Binding> binding = new AtomicReference<>();

    private NeoForgePresenceHooks() {
    }

    public static void bind(PresenceClientRuntime runtime) {
        bind(runtime, LogSink.NOOP);
    }

    public static void bind(PresenceClientRuntime runtime, LogSink logSink) {
        binding.set(runtime == null ? null : new Binding(runtime, new NeoForgePresenceAdvancementTracker(logSink)));
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

    /**
     * One fact presence observed itself, from either side.
     *
     * <p>Facts born on the client (a level rebuild, a boss bar) and facts that arrived from the optional server
     * component take the same entry and the same payload shape, so every consumer reads one fact stream and never
     * asks where a fact was born.</p>
     */
    public static void recordFact(PresenceWorldEventPayload event) {
        Binding current = binding.get();
        if (current != null && event != null) {
            current.runtime().recordWorldEvents(List.of(event));
        }
    }

    private record Binding(
            PresenceClientRuntime runtime,
            NeoForgePresenceAdvancementTracker advancementTracker
    ) {
    }
}
