package com.rheinmetal.tianshu.protocol.payload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repair step publishes what the player said, not only the ids it resolved to.
 *
 * <p>These assertions fix the shape every downstream module reads: the original utterance, the repaired one, the
 * named objects matched (with the word that was said, the registry id and the language key) and the module trigger
 * words hit (with the word that module registered).</p>
 */
class IrEvidencePayloadTest {
    @Test
    void anObjectMatchKeepsTheSpokenWordTheIdAndTheKey() {
        IrObjectMatch match = new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe");

        assertEquals("钻石锄", match.spokenWord());
        assertEquals("minecraft:diamond_hoe", match.registryId());
        assertEquals("item.minecraft.diamond_hoe", match.languageKey());
        assertFalse(match.entity());
    }

    @Test
    void theRegistryKindDecidesWhetherAMatchIsACreatureEvenWithoutAKey() {
        IrObjectMatch entity = new IrObjectMatch("僵尸", "minecraft:zombie", "entity", "");

        assertTrue(entity.entity(), "a missing language key must not turn a creature into an item");
    }

    @Test
    void aMatchWithoutSpokenWordsOrIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new IrObjectMatch("", "minecraft:stone", "item", ""));
        assertThrows(IllegalArgumentException.class, () -> new IrObjectMatch("石头", "", "item", ""));
    }

    @Test
    void theResultCarriesTheOriginalUtteranceAndBothKindsOfMatch() {
        IrResultPayload payload = new IrResultPayload(
                "帮我用钻石锄耕地",
                "帮我用钻石锄耕地",
                List.of(),
                List.of("minecraft:diamond_hoe"),
                List.of(),
                6,
                91L,
                1_000L,
                Map.of(),
                "帮我用钻石除头耕地",
                List.of(new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe")),
                List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐"))
        );

        assertEquals("帮我用钻石除头耕地", payload.rawText(), "the original utterance survives the repair");
        assertEquals("帮我用钻石锄耕地", payload.repairedText());
        assertEquals(1, payload.objectMatches().size());
        assertEquals(1, payload.moduleMatches().size());
        assertEquals("酒狐", payload.moduleMatches().get(0).registeredWord());
    }

    @Test
    void theOlderConstructorStillProducesAnEmptyEvidenceShape() {
        IrResultPayload payload = new IrResultPayload(
                "修复后", "归一化", List.of(), List.of(), List.of(), 1, 2L, 3L);

        assertEquals("", payload.rawText());
        assertEquals(List.of(), payload.objectMatches());
        assertEquals(List.of(), payload.moduleMatches());
    }
}
