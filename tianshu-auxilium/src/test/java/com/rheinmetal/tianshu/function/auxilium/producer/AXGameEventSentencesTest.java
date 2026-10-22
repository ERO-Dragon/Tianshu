package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventAppend;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXGameEventSentencesTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String PLAINS = "minecraft:plains";

    private final AXGameEventSentences sentences = new AXGameEventSentences(texts(catalog()));

    @Test
    void aDeathIsWordedEntirelyFromTheProducersOwnKeys() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.DEATH, 0),
                sample(0, 20, OVERWORLD, PLAINS), "史蒂夫被僵尸杀死了");

        assertEquals(AXGameEventKinds.DEATH, descriptor.kind());
        assertEquals("你死了：史蒂夫被僵尸杀死了", descriptor.text());
        assertEquals(AXGameEventSentences.SOURCE_ID, descriptor.sourceId());
        assertNull(descriptor.aggregateRenderer(), "a one-off moment declares no fold sentence");
        assertNull(descriptor.payload().magnitude());
    }

    @Test
    void aDeathWithoutAVanillaMessageFallsBackToTheDimensionName() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.DEATH, 0),
                sample(0, 20, OVERWORLD, PLAINS), "");

        assertEquals("你死了：主世界", descriptor.text());
    }

    @Test
    void aHealthDropCarriesItsDetailAndAFoldRendererTheLayerCanCallBack() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.HEALTH_CHANGE, 3),
                sample(8, 20, OVERWORLD, PLAINS), "");

        assertEquals("血量变化：失去 3 点生命", descriptor.text());
        assertEquals(8, descriptor.payload().magnitude(), "a folded window reads as the health that was left");
        assertNotNull(descriptor.aggregateRenderer());
        assertEquals("血量多次下降（3 次，15→8）",
                descriptor.aggregateRenderer().render(3, 15, 8));
    }

    @Test
    void aKindWithoutAMetricFoldsIntoACountSentenceOnly() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.RESPAWN, 20),
                sample(20, 20, OVERWORLD, PLAINS), "");

        assertEquals("你已复活：主世界", descriptor.text());
        assertNull(descriptor.payload().magnitude());
        assertNotNull(descriptor.aggregateRenderer());
        assertEquals("多次复活（2 次）", descriptor.aggregateRenderer().render(2, null, null));
    }

    @Test
    void aCriticalCrossingKeepsItsDetailAndStaysUnfolded() {
        AXEventDescriptor health = describe(new AXGameEventSignal(AXGameEventKinds.HEALTH_CRITICAL, 2),
                sample(2, 20, OVERWORLD, PLAINS), "");
        AXEventDescriptor hunger = describe(new AXGameEventSignal(AXGameEventKinds.HUNGER_CRITICAL, 4),
                sample(20, 4, OVERWORLD, PLAINS), "");

        assertEquals("血量危急，剩余 2 点生命", health.text());
        assertEquals("饥饿告急：饥饿值 4", hunger.text());
        assertNull(health.aggregateRenderer());
        assertNotNull(hunger.aggregateRenderer());
    }

    @Test
    void aDimensionAndABiomeUseTheirDisplayNames() {
        AXEventDescriptor dimension = describe(new AXGameEventSignal(AXGameEventKinds.DIMENSION_ENTER, 0),
                sample(20, 20, "minecraft:the_nether", "minecraft:nether_wastes"), "");
        AXEventDescriptor biome = describe(new AXGameEventSignal(AXGameEventKinds.BIOME_CHANGE, 0),
                sample(20, 20, OVERWORLD, PLAINS), "");

        assertEquals("你进入了新维度：下界", dimension.text());
        assertEquals("你来到了新生物群系：平原", biome.text());
    }

    @Test
    void anIdentifierWithoutADisplayNameStillProducesAReadableLine() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.BIOME_CHANGE, 0),
                sample(20, 20, OVERWORLD, "mymod:crystal_grove"), "");

        assertEquals("你来到了新生物群系：mymod:crystal_grove", descriptor.text());
        assertFalse(descriptor.text().contains("tianshu.ax.event"), "no language key leaks into the line");
    }

    @Test
    void aMissingLineTemplateMeansTheProducerSubmitsNothing() {
        AXGameEventSentences partial = new AXGameEventSentences(texts(Map.of(
                "tianshu.ax.event.detail.hunger_critical", "饥饿值 %s")));

        Optional<AXEventDescriptor> descriptor = partial.describe(
                new AXGameEventSignal(AXGameEventKinds.HUNGER_CRITICAL, 4),
                sample(20, 4, OVERWORLD, PLAINS), "");

        assertTrue(descriptor.isEmpty(), "a bare detail fragment is not a sentence");
    }

    @Test
    void aMissingDetailKeyDegradesToThePlainValue() {
        AXGameEventSentences partial = new AXGameEventSentences(texts(Map.of(
                "tianshu.ax.event.line.tianshu.health_change", "血量变化：%s")));

        AXEventDescriptor descriptor = partial.describe(
                new AXGameEventSignal(AXGameEventKinds.HEALTH_CHANGE, 3),
                sample(8, 20, OVERWORLD, PLAINS), "").orElseThrow();

        assertEquals("血量变化：3", descriptor.text());
    }

    @Test
    void withoutAnyLanguageTableTheProducerSubmitsNothingAtAll() {
        AXGameEventSentences silent = new AXGameEventSentences(AXGameEventTextProvider.NONE);

        Optional<AXEventDescriptor> descriptor = silent.describe(
                new AXGameEventSignal(AXGameEventKinds.DEATH, 0), sample(0, 20, OVERWORLD, PLAINS), "died");

        assertTrue(descriptor.isEmpty(), "the AX layer must never be handed an unworded event");
    }

    @Test
    void theFoldedSentenceIsSharedByTheLayerWithoutAnyRewording() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.HUNGER_CHANGE, 2),
                sample(20, 6, OVERWORLD, PLAINS), "");

        String folded = descriptor.aggregateRenderer().render(4, 12, 6);

        assertEquals("饥饿值多次下降（4 次，12→6）", folded);
        assertEquals(folded, new AXEventAppend.Aggregated(descriptor.kind(), 4, folded).text());
    }

    private AXEventDescriptor describe(AXGameEventSignal signal, AXGameEventSample sample, String deathMessage) {
        return sentences.describe(signal, sample, deathMessage).orElseThrow();
    }

    private static AXGameEventSample sample(int health, int food, String dimension, String biome) {
        return new AXGameEventSample(health, food, dimension, biome, false);
    }

    /** The producer catalog, mirroring the client language file for the kinds the probe produces. */
    private static Map<String, String> catalog() {
        Map<String, String> keys = new HashMap<>();
        keys.put("tianshu.ax.event.line.tianshu.death", "你死了：%s");
        keys.put("tianshu.ax.event.line.tianshu.respawn", "你已复活：%s");
        keys.put("tianshu.ax.event.line.tianshu.health_critical", "血量危急，%s");
        keys.put("tianshu.ax.event.line.tianshu.health_change", "血量变化：%s");
        keys.put("tianshu.ax.event.line.tianshu.hunger_critical", "饥饿告急：%s");
        keys.put("tianshu.ax.event.line.tianshu.hunger_change", "饥饿变化：%s");
        keys.put("tianshu.ax.event.line.tianshu.dimension_enter", "你进入了新维度：%s");
        keys.put("tianshu.ax.event.line.tianshu.biome_change", "你来到了新生物群系：%s");
        keys.put("tianshu.ax.event.detail.health_critical", "剩余 %s 点生命");
        keys.put("tianshu.ax.event.detail.health_change", "失去 %s 点生命");
        keys.put("tianshu.ax.event.detail.hunger_critical", "饥饿值 %s");
        keys.put("tianshu.ax.event.detail.hunger_change", "饥饿值下降 %s 点");
        keys.put("tianshu.ax.event.detail.dimension.minecraft.overworld", "主世界");
        keys.put("tianshu.ax.event.detail.dimension.minecraft.the_nether", "下界");
        keys.put("tianshu.ax.event.aggregate.tianshu.health_change", "血量多次下降（%s 次，%s→%s）");
        keys.put("tianshu.ax.event.aggregate.tianshu.hunger_critical", "饥饿多次告急（%s 次，%s→%s）");
        keys.put("tianshu.ax.event.aggregate.tianshu.hunger_change", "饥饿值多次下降（%s 次，%s→%s）");
        keys.put("tianshu.ax.event.aggregate.tianshu.respawn", "多次复活（%s 次）");
        keys.put("biome.minecraft.plains", "平原");
        return keys;
    }

    private static AXGameEventTextProvider texts(Map<String, String> keys) {
        return new AXGameEventTextProvider() {
            @Override
            public boolean exists(String key) {
                return keys.containsKey(key);
            }

            @Override
            public String text(String key, Object... args) {
                String template = keys.get(key);
                if (template == null) {
                    return "";
                }
                String rendered = template;
                for (Object arg : args) {
                    int placeholder = rendered.indexOf("%s");
                    if (placeholder < 0) {
                        break;
                    }
                    rendered = rendered.substring(0, placeholder) + arg + rendered.substring(placeholder + 2);
                }
                return rendered;
            }
        };
    }
}
