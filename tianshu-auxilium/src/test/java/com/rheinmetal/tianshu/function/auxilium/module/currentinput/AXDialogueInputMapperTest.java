package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueEntityRef;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueDeliveryPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AXDialogueInputMapperTest {
    private final AXDialogueInputMapper mapper = new AXDialogueInputMapper();

    /** The platform resolved every id, so the snapshot should read like a player would describe the scene. */
    @Test
    void displayNamesWinOverRawIds() {
        String snapshot = mapper.map(delivery(Map.of(
                "minecraft:diamond_sword", "钻石剑",
                "minecraft:zombie", "僵尸",
                "minecraft:stick", "木棍",
                "minecraft:overworld", "主世界"
        ))).deliverySnapshot();

        assertTrue(snapshot.contains("dimension=主世界"), snapshot);
        assertTrue(snapshot.contains("matchedItems=钻石剑"), snapshot);
        assertTrue(snapshot.contains("matchedEntityTypes=僵尸"), snapshot);
        assertTrue(snapshot.contains("heldItem=木棍"), snapshot);
        assertTrue(snapshot.contains("matchedEntities=村民"), snapshot);
        assertFalse(snapshot.contains("minecraft:"), "a raw id must never reach the model: " + snapshot);
    }

    /** No display name anywhere: the id degrades to its own words, still without the namespace. */
    @Test
    void withoutDisplayNamesTheIdDegradesToReadableWords() {
        String snapshot = mapper.map(delivery(Map.of())).deliverySnapshot();

        assertTrue(snapshot.contains("dimension=overworld"), snapshot);
        assertTrue(snapshot.contains("matchedItems=diamond sword"), snapshot);
        assertTrue(snapshot.contains("matchedEntityTypes=zombie"), snapshot);
        assertTrue(snapshot.contains("heldItem=stick"), snapshot);
        assertFalse(snapshot.contains("minecraft:"), snapshot);
    }

    /** The entity instance id identifies which one it is, not what the player sees, so it stays out. */
    @Test
    void theEntityLineCarriesNoInstanceId() {
        String snapshot = mapper.map(delivery(Map.of())).deliverySnapshot();

        assertTrue(snapshot.contains("matchedEntities=村民"), snapshot);
        assertFalse(snapshot.contains("entity-uuid"), snapshot);
    }

    /** The pre-existing constructor (no display names) still compiles and still produces a clean snapshot. */
    @Test
    void thePayloadWithoutDisplayNamesStillMaps() {
        DialogueDeliveryPayload legacy = new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of("wakeword"),
                List.of("minecraft:diamond_sword"),
                List.of(new DialogueEntityRef("entity-uuid", "minecraft:villager", "村民", 3.0D, false)),
                new DialogueInteractionHints("minecraft:stick", true, true, false, 2.5D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of(), Map.of("weather", "clear")),
                100L,
                1_000L
        );

        AXRequest request = mapper.map(legacy);

        assertEquals("repaired player text", request.userText());
        assertEquals("AX.session.turn", request.requestKey());
        assertEquals(AXInputSource.FORWARDED, request.source());
        assertTrue(request.deliverySnapshot().contains("matchedItems=diamond sword"), request.deliverySnapshot());
        assertFalse(request.deliverySnapshot().contains("minecraft:"), request.deliverySnapshot());
        // Only the names of the available facts belong here: the values are rendered as prose in the
        // game-context section, and dumping them again as key=value would duplicate them in a format the
        // model has no use for.
        assertTrue(request.deliverySnapshot().contains("facts=weather"));
        assertFalse(request.deliverySnapshot().contains("weather=clear"));
    }

    private static DialogueDeliveryPayload delivery(Map<String, String> displayNames) {
        return new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of("wakeword"),
                List.of("minecraft:diamond_sword"),
                List.of("minecraft:zombie"),
                List.of(new DialogueEntityRef("entity-uuid", "minecraft:villager", "村民", 3.0D, false)),
                new DialogueInteractionHints("minecraft:stick", true, true, false, 2.5D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of(), Map.of("weather", "clear")),
                100L,
                1_000L,
                displayNames
        );
    }
}
