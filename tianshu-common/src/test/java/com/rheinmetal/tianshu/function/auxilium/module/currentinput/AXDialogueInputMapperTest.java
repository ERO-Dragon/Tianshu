package com.rheinmetal.tianshu.function.auxilium.module.currentinput;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueEntityRef;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import com.rheinmetal.tianshu.function.ia.payload.DialogueDeliveryPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(request.deliverySnapshot().contains("weather=clear"));
    }
}
