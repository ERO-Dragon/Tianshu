package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.assembly.GameEventPort;
import com.rheinmetal.tianshu.api.game.GameEventFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Client side capture hook: reads the local player and hands the reading to the module that cares.
 *
 * <p>Everything above the reading is a module decision. This class therefore keeps only what needs the
 * game: health, food, dimension, biome, the death message and the sampling cadence. Which of those mean
 * "hurt" or "dying", and how they are worded, lives behind {@link GameEventPort}.</p>
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

    public static void bind(Supplier<GameEventPort> portLookup, LogSink logSink) {
        if (portLookup == null) {
            BINDING.set(null);
            return;
        }
        BINDING.set(new Binding(portLookup, logSink == null ? LogSink.NOOP : logSink));
    }

    public static void clear() {
        BINDING.set(null);
    }

    /** Drops the probe baseline so the first reading of the next world reports nothing. */
    public static void resetWorldSession() {
        Binding binding = BINDING.get();
        GameEventPort port = binding == null ? null : binding.port();
        if (port != null) {
            port.resetSession();
            binding.lastSampleAtMillis = 0L;
        }
    }

    /** Closes pending aggregation windows while the world is still addressable. */
    public static void flush() {
        Binding binding = BINDING.get();
        GameEventPort port = binding == null ? null : binding.port();
        if (port != null) {
            port.flush();
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
        GameEventPort port = binding.port();
        if (port == null) {
            return;
        }
        port.tick(nowMillis);
        if (nowMillis - binding.lastSampleAtMillis < SAMPLE_INTERVAL_MILLIS) {
            return;
        }
        binding.lastSampleAtMillis = nowMillis;
        port.sample(new GameEventFacts(
                Math.max(0, Math.round(player.getHealth())),
                player.getFoodData().getFoodLevel(),
                level.dimension().location().toString(),
                biomeId(level, player),
                player.getHealth() <= 0.0F || player.deathTime > 0,
                deathMessage(player),
                nowMillis));
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
        private final Supplier<GameEventPort> portLookup;
        @SuppressWarnings("unused")
        private final LogSink logSink;
        private long lastSampleAtMillis;

        private Binding(Supplier<GameEventPort> portLookup, LogSink logSink) {
            this.portLookup = portLookup;
            this.logSink = logSink;
        }

        private GameEventPort port() {
            try {
                return portLookup.get();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
