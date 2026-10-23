package com.rheinmetal.tianshu.function.auxilium.producer;

import com.rheinmetal.tianshu.testkit.LangShards;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the event producer's language data aligned with the code that reads it.
 *
 * <p>Two things can silently rot: the two languages drifting apart, and a rendered sentence losing its key
 * while the producer keeps asking for it. Both are failures here, and both are stated as the exact key set
 * the producer needs, so adding a produced kind without its keys is caught.</p>
 *
 * <p>The keys are read from the per-module shards ({@link LangShards}), which is the source the build merges
 * into the runtime file; the AX event vocabulary lives in this module's own shard.</p>
 */
class AXGameEventLanguageSymmetryTest {
    private static final String LINE = "tianshu.ax.event.line.";
    private static final String DETAIL = "tianshu.ax.event.detail.";
    private static final String AGGREGATE = "tianshu.ax.event.aggregate.";

    /** Kinds the built-in producer renders a single sentence for. */
    private static final List<String> PRODUCED_KINDS = List.of(
            "death", "respawn", "health_critical", "health_change",
            "hunger_critical", "hunger_change", "dimension_enter", "biome_change"
    );

    /** Kinds whose folded window is rendered from an aggregate sentence. */
    private static final List<String> FOLDED_KINDS = List.of(
            "respawn", "health_change", "hunger_critical", "hunger_change"
    );

    private static final List<String> DETAIL_SEGMENTS = List.of(
            "health_critical", "health_change", "hunger_critical", "hunger_change",
            "dimension.minecraft.overworld", "dimension.minecraft.the_nether", "dimension.minecraft.the_end"
    );

    @Test
    void bothLanguageFilesHoldExactlyTheSameKeys() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");

        assertEquals(zh, en, "zh_cn and en_us must stay symmetric");
    }

    @Test
    void everyRenderedSentenceOfTheProducerHasItsKeyInBothLanguages() throws Exception {
        Set<String> zh = keys("zh_cn.json");
        Set<String> en = keys("en_us.json");

        for (String kind : PRODUCED_KINDS) {
            assertPresent(zh, en, LINE + "tianshu." + kind);
        }
        for (String kind : FOLDED_KINDS) {
            assertPresent(zh, en, AGGREGATE + "tianshu." + kind);
        }
        for (String segment : DETAIL_SEGMENTS) {
            assertPresent(zh, en, DETAIL + segment);
        }
    }

    @Test
    void theOldLanguageKeyHandleLeavesNoSpeechKeysBehind() throws Exception {
        for (String key : keys("en_us.json")) {
            assertFalse(key.startsWith("tianshu.ax.event.speech."),
                    "the event layer no longer holds language keys, so speech keys are stale: " + key);
        }
    }

    private static void assertPresent(Set<String> zh, Set<String> en, String key) {
        assertTrue(zh.contains(key), "missing in zh_cn: " + key);
        assertTrue(en.contains(key), "missing in en_us: " + key);
    }

    private static Set<String> keys(String fileName) {
        return LangShards.keys(fileName.startsWith("zh") ? LangShards.ZH_CN : LangShards.EN_US);
    }
}
