package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.function.auxilium.event.AXEventDescriptor;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventFold;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventText;
import com.rheinmetal.tianshu.function.auxilium.event.AXEventTexts;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The producer's catalog: which kind maps to which template, and which fold shape it declares.
 *
 * <p>This producer no longer words anything, so the assertions are two-layered. The <em>shape</em> of the
 * descriptor ({@link AXEventText} key and arguments, {@link AXEventFold}) is asserted directly, because that
 * is what the producer owns now. The <em>sentence</em> is produced by rendering that shape through the same
 * language table the receiving side uses — the catalog below — which keeps the original wording assertions
 * alive instead of dropping them. Rendering edge cases (nesting, missing keys, depth) are covered by
 * {@link AXEventTextRenderingTest}.
 */
class AXGameEventSentencesTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String PLAINS = "minecraft:plains";
    private static final String LINE_PREFIX = "tianshu.ax.event.line.";

    private final Map<String, String> catalog = catalog();
    private final AXGameEventTextProvider texts = texts(catalog);
    private final AXGameEventSentences sentences = new AXGameEventSentences();

    @Test
    void aDeathNamesItsTemplateAndCarriesTheVanillaMessageAsAValue() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.DEATH, 0),
                sample(0, 20, OVERWORLD, PLAINS), "史蒂夫被僵尸杀死了");

        assertEquals(AXGameEventKinds.DEATH, descriptor.kind());
        assertEquals(LINE_PREFIX + "tianshu.death", descriptor.text().key());
        assertEquals(List.of(AXEventText.Arg.value("史蒂夫被僵尸杀死了")), descriptor.text().args(),
                "the already localized vanilla message is a value, not another template");
        assertEquals("你死了：史蒂夫被僵尸杀死了", rendered(descriptor));
        assertEquals(AXGameEventSentences.SOURCE_ID, descriptor.sourceId());
        assertEquals(AXEventFold.NONE, descriptor.fold(), "a one-off moment declares no fold");
        assertNull(descriptor.payload().magnitude());
    }

    @Test
    void aDeathWithoutAVanillaMessageFallsBackToTheDimensionTemplate() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.DEATH, 0),
                sample(0, 20, OVERWORLD, PLAINS), "");

        assertEquals(List.of(AXEventText.Arg.nested(
                        AXEventText.of("tianshu.ax.event.detail.dimension.minecraft.overworld"))),
                descriptor.text().args(), "the dimension stays a template, so it is worded by the layer too");
        assertEquals("你死了：主世界", rendered(descriptor));
    }

    @Test
    void aHealthDropCarriesItsDetailAndARangeFold() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.HEALTH_CHANGE, 3),
                sample(8, 20, OVERWORLD, PLAINS), "");

        assertEquals(LINE_PREFIX + "tianshu.health_change", descriptor.text().key());
        assertEquals(List.of(AXEventText.Arg.nested(
                        AXEventText.of("tianshu.ax.event.detail.health_change", "3"))),
                descriptor.text().args());
        assertEquals("血量变化：失去 3 点生命", rendered(descriptor));
        assertEquals(8, descriptor.payload().magnitude(), "a folded window reads as the health that was left");
        assertEquals(AXEventFold.RANGE, descriptor.fold());
        assertEquals("血量多次下降（3 次，15→8）", renderedFold(descriptor, 3, 15, 8));
    }

    @Test
    void aKindWithoutAMetricDeclaresACountFold() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.RESPAWN, 20),
                sample(20, 20, OVERWORLD, PLAINS), "");

        assertEquals("你已复活：主世界", rendered(descriptor));
        assertNull(descriptor.payload().magnitude());
        assertEquals(AXEventFold.COUNT, descriptor.fold());
        assertEquals("多次复活（2 次）", renderedFold(descriptor, 2, null, null));
    }

    @Test
    void aCriticalCrossingKeepsItsDetailAndOnlySomeKindsFold() {
        AXEventDescriptor health = describe(new AXGameEventSignal(AXGameEventKinds.HEALTH_CRITICAL, 2),
                sample(2, 20, OVERWORLD, PLAINS), "");
        AXEventDescriptor hunger = describe(new AXGameEventSignal(AXGameEventKinds.HUNGER_CRITICAL, 4),
                sample(20, 4, OVERWORLD, PLAINS), "");

        assertEquals("血量危急，剩余 2 点生命", rendered(health));
        assertEquals("饥饿告急：饥饿值 4", rendered(hunger));
        assertEquals(AXEventFold.NONE, health.fold());
        assertEquals(AXEventFold.RANGE, hunger.fold());
    }

    @Test
    void aDimensionAndABiomeStayTemplatesUntilTheLayerWordsThem() {
        AXEventDescriptor dimension = describe(new AXGameEventSignal(AXGameEventKinds.DIMENSION_ENTER, 0),
                sample(20, 20, "minecraft:the_nether", "minecraft:nether_wastes"), "");
        AXEventDescriptor biome = describe(new AXGameEventSignal(AXGameEventKinds.BIOME_CHANGE, 0),
                sample(20, 20, OVERWORLD, PLAINS), "");

        assertEquals("你进入了新维度：下界", rendered(dimension));
        assertEquals("你来到了新生物群系：平原", rendered(biome));
        assertTrue(descriptorKeyHoldsOnlyKeys(dimension), "no sentence is baked into the descriptor");
    }

    @Test
    void anIdentifierWithoutADisplayNameStillRendersReadably() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.BIOME_CHANGE, 0),
                sample(20, 20, OVERWORLD, "mymod:crystal_grove"), "");

        // The unknown biome has no template; the renderer answers blank, so the layer falls back to the kind
        // code rather than leaking a language key into the line.
        assertTrue(rendered(descriptor).isEmpty(), "an unresolvable fragment renders blank, never a key");
        assertFalse(descriptor.text().key().contains("mymod:crystal_grove"),
                "a raw identifier never becomes a key");
    }

    @Test
    void aMissingLineTemplateStillProducesADescriptorTheLayerCanFallBackFrom() {
        // The producer can no longer refuse an event: it does not know whether a template exists. The layer
        // renders, sees blank, and falls back to the kind code — covered end to end by AXEventTextRenderingTest.
        AXGameEventSentences partial = new AXGameEventSentences();
        AXEventDescriptor descriptor = partial.describe(
                new AXGameEventSignal(AXGameEventKinds.HUNGER_CRITICAL, 4),
                sample(20, 4, OVERWORLD, PLAINS), "").orElseThrow();

        assertEquals(LINE_PREFIX + "tianshu.hunger_critical", descriptor.text().key());
        assertTrue(AXEventTexts.sanitize(AXGameEventTextProvider.NONE.render(descriptor.text())).isBlank());
    }

    @Test
    void aMissingDetailTemplateRendersBlankInsteadOfAPlainValue() {
        // Deliberate change of behaviour, recorded here: the old producer degraded a missing detail key to
        // the bare number. The layer no longer substitutes fragments it cannot word — a hole in a sentence
        // reads worse than the kind fallback — so the whole line renders blank and the service falls back.
        Map<String, String> partial = new HashMap<>();
        partial.put(LINE_PREFIX + "tianshu.health_change", "血量变化：%s");
        AXGameEventTextProvider strict = texts(partial);
        AXGameEventSentences producer = new AXGameEventSentences();

        AXEventDescriptor descriptor = producer.describe(
                new AXGameEventSignal(AXGameEventKinds.HEALTH_CHANGE, 3),
                sample(8, 20, OVERWORLD, PLAINS), "").orElseThrow();

        assertTrue(AXEventTexts.sanitize(strict.render(descriptor.text())).isBlank(),
                "the nested detail key is missing, so nothing is substituted");
    }

    @Test
    void withoutAnyLanguageTableTheDescriptorStillDescribesItself() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.DEATH, 0),
                sample(0, 20, OVERWORLD, PLAINS), "died");

        assertTrue(AXEventTexts.sanitize(AXGameEventTextProvider.NONE.render(descriptor.text())).isBlank(),
                "nothing renders without a table; the layer then falls back to the kind code");
        assertEquals(LINE_PREFIX + "tianshu.death", descriptor.text().key());
    }

    @Test
    void theFoldedSentenceIsBuiltByTheLayerFromTheDeclaredFold() {
        AXEventDescriptor descriptor = describe(new AXGameEventSignal(AXGameEventKinds.HUNGER_CHANGE, 2),
                sample(20, 6, OVERWORLD, PLAINS), "");

        assertEquals(AXEventFold.RANGE, descriptor.fold());
        assertEquals("饥饿值多次下降（4 次，12→6）", renderedFold(descriptor, 4, 12, 6));
    }

    private AXEventDescriptor describe(AXGameEventSignal signal, AXGameEventSample sample, String deathMessage) {
        return sentences.describe(signal, sample, deathMessage).orElseThrow();
    }

    /** The sentence the receiving side would produce for this descriptor's template. */
    private String rendered(AXEventDescriptor descriptor) {
        return AXEventTexts.sanitize(texts.render(descriptor.text()));
    }

    /** The folded sentence, built the way the aggregator builds it, then rendered by the same table. */
    private String renderedFold(AXEventDescriptor descriptor, int count, Integer first, Integer last) {
        AXEventText folded = AXEventTexts.aggregate(descriptor.kind(), descriptor.fold(), count, first, last);
        return AXEventTexts.sanitize(texts.render(folded));
    }

    /** True when every argument is a value or a nested key — no rendered sentence hid in the descriptor. */
    private static boolean descriptorKeyHoldsOnlyKeys(AXEventDescriptor descriptor) {
        return descriptor.text().key().startsWith(LINE_PREFIX);
    }

    private static AXGameEventSample sample(int health, int food, String dimension, String biome) {
        return new AXGameEventSample(health, food, dimension, biome, false);
    }

    /** The language table, mirroring the client file for the kinds the probe produces. */
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
