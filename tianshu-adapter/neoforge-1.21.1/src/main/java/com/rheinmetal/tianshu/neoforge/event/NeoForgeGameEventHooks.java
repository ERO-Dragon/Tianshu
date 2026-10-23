package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventProbe;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventSample;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventSentences;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventSignal;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.producer.AXGameEventService;
import com.rheinmetal.tianshu.neoforge.adapter.NeoForgeGameEventTextProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Client side capture hook: reads the local player, asks the probe for threshold crossings, lets the
 * producer render its sentences and submits the descriptor to the AX event service.
 *
 * <p>This hook is an ordinary producer. It builds the same {@link AXEventDescriptor} any other module
 * would build - a kind, a payload, the sentence {@link AXGameEventSentences} rendered from the client
 * language table and an optional fold renderer - and it calls the same
 * {@link AXGameEventService#submit(AXEventDescriptor)} that is reachable from the module service
 * registry. Nothing here is privileged: the service never inspects the source it was handed, so this
 * producer cannot take a shortcut and no wording is assembled on the AX side.
 *
 * <p>Everything above the probe is deliberately thin: the state machine lives in the platform neutral
 * probe so it can be unit tested, and the wording lives in language keys so no sentence is hardcoded
 * here.
 *
 * <p>Production stops when the bound service is gone. The AX module registers that service on world
 * start and unregisters it on world stop, so a stale hook cannot produce events outside a session.
 */
public final class NeoForgeGameEventHooks {
    /**
     * Sampling cadence. Crossings are seconds apart at the fastest, so four readings per second keeps
     * the per tick cost and the per tick allocation off the render thread without missing a crossing.
     */
    private static final long SAMPLE_INTERVAL_MILLIS = 250L;

    private static final AtomicReference<Binding> BINDING = new AtomicReference<>();

    private NeoForgeGameEventHooks() {
    }

    public static void bind(Supplier<AXGameEventService> serviceLookup, LogSink logSink) {
        if (serviceLookup == null) {
            BINDING.set(null);
            return;
        }
        BINDING.set(new Binding(
                serviceLookup,
                logSink == null ? LogSink.NOOP : logSink,
                new AXGameEventProbe(),
                new AXGameEventSentences()
        ));
    }

    public static void clear() {
        BINDING.set(null);
    }

    /** Drops the probe baseline so the first reading of the next world reports nothing. */
    public static void resetWorldSession() {
        Binding binding = BINDING.get();
        if (binding != null) {
            binding.probe.reset();
            binding.lastSampleAtMillis = 0L;
        }
    }

    /** Closes pending aggregation windows while the world is still addressable. */
    public static void flush() {
        Binding binding = BINDING.get();
        if (binding == null) {
            return;
        }
        AXGameEventService service = binding.service();
        if (service != null) {
            service.flush();
        }
    }

    /** Called from the client tick hook. */
    public static void tick(Minecraft minecraft) {
        Binding binding = BINDING.get();
        if (binding == null || minecraft == null) {
            return;
        }
        Player player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return;
        }
        long nowMillis = System.currentTimeMillis();
        AXGameEventService service = binding.service();
        if (service != null && !service.closed()) {
            // The deferred window runs on the wall clock, so it is advanced on every tick rather than only when a sample
            // is due: otherwise the voluntary turn would wait for the next event to be submitted.
            service.tick(nowMillis);
        }
        if (nowMillis - binding.lastSampleAtMillis < SAMPLE_INTERVAL_MILLIS) {
            return;
        }
        binding.lastSampleAtMillis = nowMillis;
        if (service == null || service.closed()) {
            return;
        }
        AXGameEventSample sample = new AXGameEventSample(
                Math.max(0, Math.round(player.getHealth())),
                player.getFoodData().getFoodLevel(),
                level.dimension().location().toString(),
                biomeId(level, player),
                player.getHealth() <= 0.0F || player.deathTime > 0
        );
        List<AXGameEventSignal> signals = binding.probe.sample(sample);
        if (signals.isEmpty()) {
            return;
        }
        String deathMessage = deathMessage(player);
        for (AXGameEventSignal signal : signals) {
            Optional<AXEventDescriptor> descriptor = binding.sentences.describe(signal, sample, deathMessage);
            if (descriptor.isEmpty()) {
                binding.logSink.warn("neoforge.ax.event.unrenderable_signal kind=" + signal.kind());
                continue;
            }
            service.submit(descriptor.get());
        }
    }

    /** The vanilla death message, empty when the combat tracker cannot name one. */
    private static String deathMessage(Player player) {
        try {
            String message = player.getCombatTracker().getDeathMessage().getString();
            return message == null ? "" : message.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String biomeId(Level level, Player player) {
        if (level == null || player == null) {
            return "";
        }
        try {
            return level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static final class Binding {
        private final Supplier<AXGameEventService> serviceLookup;
        private final LogSink logSink;
        private final AXGameEventProbe probe;
        private final AXGameEventSentences sentences;
        private long lastSampleAtMillis;

        private Binding(Supplier<AXGameEventService> serviceLookup, LogSink logSink, AXGameEventProbe probe,
                        AXGameEventSentences sentences) {
            this.serviceLookup = serviceLookup;
            this.logSink = logSink;
            this.probe = probe;
            this.sentences = sentences;
        }

        private AXGameEventService service() {
            try {
                return serviceLookup.get();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
