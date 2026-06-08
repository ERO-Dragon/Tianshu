package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.function.ia.context.DialogueContextFrame;
import com.rheinmetal.tianshu.function.ia.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.function.ia.context.DialogueInteractionHints;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueContextFreezeStoreTest {
    @Test
    void endedSpeechContextStaysAvailableForFinalText() {
        DialogueContextFreezeStore store = new DialogueContextFreezeStore(Duration.ofSeconds(10));
        DialogueContextFrame frame = new DialogueContextFrame(
                new DialogueInteractionHints("create:wrench", true, false, false, 3.0D, List.of()),
                new DialogueContextSnapshot("player", "minecraft:overworld", List.of(), List.of("mod:helmet"), Map.of())
        );

        store.freeze(42L, frame, 1_000L);
        store.markEnded(42L, 2_000L);

        DialogueContextFrame restored = store.consume(42L, 3_000L).orElseThrow();

        assertEquals("create:wrench", restored.interactionHints().heldItemId());
        assertEquals(List.of("mod:helmet"), restored.contextSnapshot().equippedItemIds());
        assertTrue(store.consume(42L, 3_001L).isEmpty());
    }

    @Test
    void endedSpeechContextExpiresAfterRetention() {
        DialogueContextFreezeStore store = new DialogueContextFreezeStore(Duration.ofSeconds(10));
        store.freeze(42L, DialogueContextFrame.empty("player"), 1_000L);
        store.markEnded(42L, 2_000L);

        assertTrue(store.consume(42L, 13_001L).isEmpty());
    }
}
