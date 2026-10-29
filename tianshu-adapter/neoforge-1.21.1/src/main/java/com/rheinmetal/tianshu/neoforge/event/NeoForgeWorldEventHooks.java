package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.game.WorldEventPublisher;
import com.rheinmetal.tianshu.protocol.payload.WorldEventPayload;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The client's single entry for "the game did something".
 *
 * <p>Every producer ends here — an advancement packet caught by the mixin, a level rebuild, a boss bar, the optional
 * server component's facts — and this class is the only caller of the host's {@link WorldEventPublisher}. The shape on
 * the wire is the api's raw {@link WorldEventPayload}; nothing here knows which module consumes the stream, or whether
 * one is installed at all.</p>
 *
 * <p>The binding is per session: an event that arrives after the runtime is gone is dropped, never published into a
 * dead protocol. Binding and clearing take the publisher instance so a late clear cannot unbind a newer session.</p>
 */
public final class NeoForgeWorldEventHooks {
    private static final AtomicReference<Binding> binding = new AtomicReference<>();

    private NeoForgeWorldEventHooks() {
    }

    public static void bind(WorldEventPublisher publisher) {
        bind(publisher, LogSink.NOOP);
    }

    public static void bind(WorldEventPublisher publisher, LogSink logSink) {
        binding.set(publisher == null ? null : new Binding(publisher, new NeoForgeAdvancementEventTracker(logSink)));
    }

    public static void clear(WorldEventPublisher publisher) {
        Binding current = binding.get();
        if (current != null && current.publisher() == publisher) {
            binding.compareAndSet(current, null);
        }
    }

    /** A new world starts from a fresh progress baseline: joining is not "unlocking everything already done". */
    public static void resetWorldSession() {
        Binding current = binding.get();
        if (current != null) {
            current.advancementTracker().reset();
        }
    }

    public static void recordAdvancementUpdate(ClientboundUpdateAdvancementsPacket packet) {
        Binding current = binding.get();
        if (current != null) {
            current.advancementTracker().collect(packet).forEach(current.publisher()::publish);
        }
    }

    /**
     * One fact the host observed itself, from either side.
     *
     * <p>Facts born on the client (a level rebuild, a boss bar) and facts that arrived from the optional server
     * component take the same entry and the same shape, so every consumer reads one stream and never asks where a
     * fact was born.</p>
     */
    public static void publish(WorldEventPayload event) {
        Binding current = binding.get();
        if (current != null && event != null) {
            current.publisher().publish(event);
        }
    }

    private record Binding(
            WorldEventPublisher publisher,
            NeoForgeAdvancementEventTracker advancementTracker
    ) {
    }
}
