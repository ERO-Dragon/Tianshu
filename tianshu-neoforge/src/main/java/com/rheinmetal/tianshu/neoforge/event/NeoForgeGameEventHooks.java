package com.rheinmetal.tianshu.neoforge.event;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.event.AXGameEventKinds;
import com.rheinmetal.tianshu.client.event.AXGameEventProbe;
import com.rheinmetal.tianshu.client.event.AXGameEventSample;
import com.rheinmetal.tianshu.client.event.AXGameEventSignal;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventPayload;
import com.rheinmetal.tianshu.function.auxilium.event.AXGameEventService;
import com.rheinmetal.tianshu.function.auxilium.module.system.AXPromptLanguage;
import com.rheinmetal.tianshu.neoforge.adapter.ClientLanguagePolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Client side capture hook: reads the local player, asks the probe for threshold crossings, resolves
 * the player facing wording and hands an event to the AX event service.
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

    private static final String DETAIL_KEY_PREFIX = "tianshu.ax.event.detail.";
    private static final String DETAIL_HEALTH_CRITICAL = DETAIL_KEY_PREFIX + "health_critical";
    private static final String DETAIL_HEALTH_CHANGE = DETAIL_KEY_PREFIX + "health_change";
    private static final String DETAIL_HUNGER_CRITICAL = DETAIL_KEY_PREFIX + "hunger_critical";
    private static final String DETAIL_HUNGER_CHANGE = DETAIL_KEY_PREFIX + "hunger_change";
    private static final String DETAIL_DIMENSION_PREFIX = DETAIL_KEY_PREFIX + "dimension.";

    private static final AtomicReference<Binding> BINDING = new AtomicReference<>();

    private NeoForgeGameEventHooks() {
    }

    public static void bind(Supplier<AXGameEventService> serviceLookup, Supplier<String> languageCode, LogSink logSink) {
        if (serviceLookup == null) {
            BINDING.set(null);
            return;
        }
        BINDING.set(new Binding(
                serviceLookup,
                languageCode == null ? () -> AXPromptLanguage.EN_US.code() : languageCode,
                logSink == null ? LogSink.NOOP : logSink,
                new AXGameEventProbe()
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
            service.flush(System.currentTimeMillis());
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
        if (nowMillis - binding.lastSampleAtMillis < SAMPLE_INTERVAL_MILLIS) {
            return;
        }
        binding.lastSampleAtMillis = nowMillis;
        AXGameEventService service = binding.service();
        if (service == null || service.closed()) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        List<AXGameEventSignal> signals = binding.probe.sample(new AXGameEventSample(
                Math.max(0, Math.round(player.getHealth())),
                player.getFoodData().getFoodLevel(),
                dimensionId,
                biomeId(level, player),
                player.getHealth() <= 0.0F || player.deathTime > 0
        ));
        if (signals.isEmpty()) {
            return;
        }
        for (AXGameEventSignal signal : signals) {
            AXEventPayload payload = payloadFor(binding, signal, player, dimensionId);
            if (payload == null) {
                binding.logSink.warn("neoforge.ax.event.unmapped_kind kind=" + signal.kind());
                continue;
            }
            service.submit(AXEventDescriptor.of(signal.kind(), payload), nowMillis);
        }
    }

    private static AXEventPayload payloadFor(Binding binding, AXGameEventSignal signal, Player player, String dimensionId) {
        return switch (signal.kind()) {
            case AXGameEventKinds.HEALTH_CRITICAL -> AXEventPayload.of(
                    text(binding, DETAIL_HEALTH_CRITICAL, signal.value()),
                    1,
                    signal.value());
            case AXGameEventKinds.HEALTH_CHANGE -> AXEventPayload.of(
                    text(binding, DETAIL_HEALTH_CHANGE, signal.value()),
                    1,
                    Math.max(0, Math.round(player.getHealth())));
            case AXGameEventKinds.HUNGER_CRITICAL -> AXEventPayload.of(
                    text(binding, DETAIL_HUNGER_CRITICAL, signal.value()),
                    1,
                    signal.value());
            case AXGameEventKinds.HUNGER_CHANGE -> AXEventPayload.of(
                    text(binding, DETAIL_HUNGER_CHANGE, signal.value()),
                    1,
                    signal.value());
            case AXGameEventKinds.DIMENSION_ENTER -> AXEventPayload.of(dimensionName(dimensionId), 1, null);
            case AXGameEventKinds.BIOME_CHANGE -> AXEventPayload.of(
                    biomeName(binding, player), 1, null);
            case AXGameEventKinds.DEATH -> AXEventPayload.of(deathDetail(player, dimensionId), 1, null);
            case AXGameEventKinds.RESPAWN -> AXEventPayload.of(dimensionName(dimensionId), 1, null);
            default -> null;
        };
    }

    /** Resolves a language key, falling back to the raw number so history never holds an unresolved key. */
    private static String text(Binding binding, String key, int value) {
        try {
            return Component.translatable(key, value).getString();
        } catch (RuntimeException failure) {
            binding.logSink.warn("neoforge.ax.event.text_failed key=" + key);
            return Integer.toString(value);
        }
    }

    private static String dimensionName(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "";
        }
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        if (id == null) {
            return dimensionId;
        }
        String key = DETAIL_DIMENSION_PREFIX + id.getNamespace() + "." + id.getPath().replace('/', '.');
        try {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the readable identifier.
        }
        return ClientLanguagePolicy.englishName(id);
    }

    private static String biomeName(Binding binding, Player player) {
        String biomeId = biomeId(player.level(), player);
        if (biomeId.isBlank()) {
            return "";
        }
        ResourceLocation id = ResourceLocation.tryParse(biomeId);
        return id == null ? biomeId : ClientLanguagePolicy.registryDisplayName(id, "biome", binding.languageCode.get());
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

    private static String deathDetail(Player player, String dimensionId) {
        try {
            String message = player.getCombatTracker().getDeathMessage().getString();
            if (message != null && !message.isBlank()) {
                return message.trim();
            }
        } catch (RuntimeException ignored) {
            // Fall back to the dimension so the event still carries meaning.
        }
        return dimensionName(dimensionId);
    }

    private static final class Binding {
        private final Supplier<AXGameEventService> serviceLookup;
        private final Supplier<String> languageCode;
        private final LogSink logSink;
        private final AXGameEventProbe probe;
        private long lastSampleAtMillis;

        private Binding(Supplier<AXGameEventService> serviceLookup, Supplier<String> languageCode, LogSink logSink,
                        AXGameEventProbe probe) {
            this.serviceLookup = serviceLookup;
            this.languageCode = languageCode;
            this.logSink = logSink;
            this.probe = probe;
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
