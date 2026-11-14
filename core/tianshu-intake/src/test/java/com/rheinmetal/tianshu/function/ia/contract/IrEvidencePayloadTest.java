package com.rheinmetal.tianshu.function.ia.contract;

import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.stream.Stream;

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
                "帮我用钻石除头耕地",
                List.of(new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe")),
                List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐")),
                6,
                91L,
                1_000L
        );

        assertEquals("帮我用钻石除头耕地", payload.rawText(), "the original utterance survives the repair");
        assertEquals("帮我用钻石锄耕地", payload.repairedText());
        assertEquals(1, payload.objectMatches().size());
        assertEquals("钻石锄", payload.objectMatches().get(0).spokenWord());
        assertEquals(1, payload.moduleMatches().size());
        assertEquals("module.maid", payload.moduleMatches().get(0).moduleId());
        assertEquals("酒狐", payload.moduleMatches().get(0).registeredWord());
    }

    /** Null evidence is "nothing matched", never a null list a consumer has to guard against. */
    @Test
    void absentEvidenceNormalizesToEmptyListsAndEmptyText() {
        IrResultPayload payload = new IrResultPayload("修复后", null, null, null, null, 1, 2L, 3L);

        assertEquals("", payload.rawText());
        assertEquals("", payload.normalizedText());
        assertEquals(List.of(), payload.objectMatches());
        assertEquals(List.of(), payload.moduleMatches());
    }

    /**
     * The record is the contract: the evidence is two match lists and nothing restates them.
     *
     * <p>A parallel id list or an id-to-name map would let a reader pick the flatter field and lose the pairing that
     * makes the match evidence, so the shape itself is pinned here rather than left to convention.</p>
     */
    @Test
    void theEvidenceShapeIsOnlyTextAndTwoMatchLists() {
        List<String> components = Stream.of(IrResultPayload.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertEquals(List.of(
                "repairedText",
                "normalizedText",
                "rawText",
                "objectMatches",
                "moduleMatches",
                "turnId",
                "sessionId",
                "timestampMillis"
        ), components);
    }
}
