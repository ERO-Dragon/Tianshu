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
    @Test
    void mapsOnlyRepairedTextAsUserText() {
        DialogueDeliveryPayload delivery = new DialogueDeliveryPayload(
                "session",
                "request",
                "player",
                "turn",
                "repaired player text",
                "normalized text should not be used",
                List.of("wakeword"),
                List.of("minecraft:diamond_sword"),
                List.of(new DialogueEntityRef("entity-uuid", "minecraft:villager", "Villager", 3.0D, false)),
                new DialogueInteractionHints("minecraft:stick", true, true, false, 2.5D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of(), Map.of("weather", "clear")),
                100L,
                1_000L
        );

        AXRequest request = new AXDialogueInputMapper().map(delivery);

        assertEquals("repaired player text", request.userText());
        assertEquals("AX.session.turn", request.requestKey());
        assertEquals(AXInputSource.FORWARDED, request.source());
        assertTrue(request.deliverySnapshot().contains("matchedWakeWords=wakeword"));
        assertTrue(request.deliverySnapshot().contains("matchedEntities=minecraft:villager#entity-uuid"));
        assertTrue(request.deliverySnapshot().contains("heldItem=minecraft:stick"));
        // Only the names of the available facts belong here: the values are rendered as prose in the
        // game-context section, and dumping them again as key=value would duplicate them in a format the
        // model has no use for.
        assertTrue(request.deliverySnapshot().contains("facts=weather"));
        assertFalse(request.deliverySnapshot().contains("weather=clear"));
    }
}
