package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.contract.DialogueDeliveryPayload;
import com.rheinmetal.tianshu.function.ia.contract.IrModuleMatch;
import com.rheinmetal.tianshu.function.ia.contract.IrObjectMatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXDialogueInputMapperTest {
    private final AXDialogueInputMapper mapper = new AXDialogueInputMapper();

    /** The platform resolved the world ids it owns, so the snapshot should read like a player would describe the scene. */
    @Test
    void displayNamesWinOverRawIds() {
        String snapshot = mapper.map(delivery(Map.of(
                "minecraft:stick", "木棍",
                "minecraft:overworld", "主世界"
        ))).deliverySnapshot();

        assertTrue(snapshot.contains("dimension=主世界"), snapshot);
        assertTrue(snapshot.contains("heldItem=木棍"), snapshot);
        assertTrue(snapshot.contains("matchedEntities=村民"), snapshot);
        assertFalse(snapshot.contains("minecraft:"), "a raw id must never reach the model: " + snapshot);
    }

    /** A matched object is shown as the words the player said: those are the only name such a match has. */
    @Test
    void matchedObjectsShowTheWordsThePlayerUsed() {
        String snapshot = mapper.map(delivery(Map.of())).deliverySnapshot();

        assertTrue(snapshot.contains("matchedObjects=钻石锄, 女仆"), snapshot);
        assertFalse(snapshot.contains("minecraft:"), snapshot);
        assertFalse(snapshot.contains("diamond hoe"),
                "a match with evidence is named by its spoken word, not by a degraded id: " + snapshot);
    }

    /** No display name anywhere: the id degrades to its own words, still without the namespace. */
    @Test
    void withoutDisplayNamesTheIdDegradesToReadableWords() {
        DialogueDeliveryPayload bare = new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of(new DialogueEntityRef("entity-uuid", "minecraft:villager", "", 3.0D, false)),
                new DialogueInteractionHints("minecraft:stick", true, true, false, 2.5D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of(), Map.of("weather", "clear")),
                100L,
                1_000L
        );

        String snapshot = mapper.map(bare).deliverySnapshot();

        assertTrue(snapshot.contains("dimension=overworld"), snapshot);
        assertTrue(snapshot.contains("matchedEntities=villager"), snapshot);
        assertTrue(snapshot.contains("heldItem=stick"), snapshot);
        assertFalse(snapshot.contains("matchedObjects="), "no matches means no line, not a degraded one: " + snapshot);
        assertFalse(snapshot.contains("minecraft:"), snapshot);
    }

    /** The entity instance id identifies which one it is, not what the player sees, so it stays out. */
    @Test
    void theEntityLineCarriesNoInstanceId() {
        String snapshot = mapper.map(delivery(Map.of())).deliverySnapshot();

        assertTrue(snapshot.contains("matchedEntities=村民"), snapshot);
        assertFalse(snapshot.contains("entity-uuid"), snapshot);
    }

    /** The delivery still maps end to end, and the facts stay as names only. */
    @Test
    void theDeliveryMapsAndKeepsFactValuesOutOfTheSnapshot() {
        AXRequest request = mapper.map(delivery(Map.of()));

        assertEquals("repaired player text", request.userText());
        assertEquals("AX.session.turn", request.requestKey());
        assertEquals(AXInputSource.FORWARDED, request.source());
        // Only the names of the available facts belong here: the values are rendered as prose in the
        // game-context section, and dumping them again as key=value would duplicate them in a format the
        // model has no use for.
        assertTrue(request.deliverySnapshot().contains("facts=weather"), request.deliverySnapshot());
        assertFalse(request.deliverySnapshot().contains("weather=clear"), request.deliverySnapshot());
    }

    private static DialogueDeliveryPayload delivery(Map<String, String> displayNames) {
        return new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of(new DialogueEntityRef("entity-uuid", "minecraft:villager", "村民", 3.0D, false)),
                new DialogueInteractionHints("minecraft:stick", true, true, false, 2.5D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of(), Map.of("weather", "clear")),
                100L,
                1_000L,
                displayNames,
                List.of(
                        new IrObjectMatch("钻石锄", "minecraft:diamond_hoe", "item", "item.minecraft.diamond_hoe"),
                        new IrObjectMatch("女仆", "touhou_little_maid:maid", "entity", "entity.touhou_little_maid.maid")
                ),
                List.of(new IrModuleMatch("module.maid", "酒狐", "酒狐"))
        );
    }
}
