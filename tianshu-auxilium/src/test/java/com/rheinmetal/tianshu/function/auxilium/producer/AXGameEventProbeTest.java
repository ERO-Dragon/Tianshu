package com.rheinmetal.tianshu.function.auxilium.producer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameEventProbeTest {
    private final AXGameEventProbe probe = new AXGameEventProbe();

    @Test
    void firstSampleOnlyEstablishesTheBaseline() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        assertTrue(probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false)).isEmpty());
    }

    @Test
    void healthCrossingEmitsCriticalOnlyOnce() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        List<String> kinds = new ArrayList<>();
        for (int health : new int[]{12, 6, 4, 3, 2}) {
            probe.sample(sample(health, 20, "minecraft:overworld", "minecraft:plains", false))
                    .forEach(signal -> kinds.add(signal.kind()));
        }

        assertEquals(1, count(kinds, AXGameEventKinds.HEALTH_CRITICAL));
        assertEquals(5, count(kinds, AXGameEventKinds.HEALTH_CHANGE));
    }

    @Test
    void healingDoesNotProduceAHealthChange() {
        probe.sample(sample(8, 20, "minecraft:overworld", "minecraft:plains", false));

        List<AXGameEventSignal> signals = probe.sample(sample(16, 20, "minecraft:overworld", "minecraft:plains", false));

        assertTrue(signals.isEmpty());
    }

    @Test
    void deathEmitsOnceAndSwallowsTheFinalHealthDrop() {
        probe.sample(sample(6, 20, "minecraft:overworld", "minecraft:plains", false));

        List<AXGameEventSignal> death = probe.sample(sample(0, 20, "minecraft:overworld", "minecraft:plains", true));
        List<AXGameEventSignal> stillDown = probe.sample(sample(0, 20, "minecraft:overworld", "minecraft:plains", true));

        assertEquals(1, death.size());
        assertEquals(AXGameEventKinds.DEATH, death.get(0).kind());
        assertTrue(stillDown.isEmpty());
    }

    @Test
    void respawnEmitsOnceAndDoesNotReportTheRefill() {
        probe.sample(sample(6, 20, "minecraft:overworld", "minecraft:plains", false));
        probe.sample(sample(0, 20, "minecraft:overworld", "minecraft:plains", true));

        List<AXGameEventSignal> respawn = probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        assertEquals(1, respawn.size());
        assertEquals(AXGameEventKinds.RESPAWN, respawn.get(0).kind());
        assertEquals(20, respawn.get(0).value());
    }

    @Test
    void dimensionChangeAlsoSwallowsTheBiomeChangeItCauses() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        List<AXGameEventSignal> signals = probe.sample(sample(20, 20, "minecraft:the_nether", "minecraft:nether_wastes", false));

        assertEquals(1, signals.size());
        assertEquals(AXGameEventKinds.DIMENSION_ENTER, signals.get(0).kind());
    }

    @Test
    void biomeChangeWithinOneDimensionIsReported() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        List<AXGameEventSignal> signals = probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:forest", false));

        assertEquals(1, signals.size());
        assertEquals(AXGameEventKinds.BIOME_CHANGE, signals.get(0).kind());
    }

    @Test
    void hungerCrossingEmitsCriticalOnlyOnce() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));

        List<String> kinds = new ArrayList<>();
        for (int food : new int[]{12, 7, 6, 4, 2}) {
            probe.sample(sample(20, food, "minecraft:overworld", "minecraft:plains", false))
                    .forEach(signal -> kinds.add(signal.kind()));
        }

        assertEquals(1, count(kinds, AXGameEventKinds.HUNGER_CRITICAL));
        assertEquals(5, count(kinds, AXGameEventKinds.HUNGER_CHANGE));
    }

    @Test
    void resetStartsFromAFreshBaseline() {
        probe.sample(sample(20, 20, "minecraft:overworld", "minecraft:plains", false));
        probe.reset();

        List<AXGameEventSignal> signals = probe.sample(sample(2, 2, "minecraft:the_nether", "minecraft:nether_wastes", false));

        assertTrue(signals.isEmpty());
    }

    private static long count(List<String> kinds, String kind) {
        return kinds.stream().filter(kind::equals).count();
    }

    private static AXGameEventSample sample(int health, int food, String dimension, String biome, boolean dying) {
        return new AXGameEventSample(health, food, dimension, biome, dying);
    }
}
