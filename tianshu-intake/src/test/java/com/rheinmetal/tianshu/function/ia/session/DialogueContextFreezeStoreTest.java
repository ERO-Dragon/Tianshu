package com.rheinmetal.tianshu.function.ia.session;

import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextFrame;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueContextSnapshot;
import com.rheinmetal.tianshu.protocol.dialogue.context.DialogueInteractionHints;
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
                new DialogueInteractionHints("create:wrench", true, false, false, 3.0D),
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

    @Test
    void retainedFramesAreCappedSoAFailedTurnCannotGrowTheStoreForever() {
        DialogueContextFreezeStore store = new DialogueContextFreezeStore(Duration.ofSeconds(30));
        for (long sessionId = 1L; sessionId <= 100L; sessionId++) {
            store.freeze(sessionId, DialogueContextFrame.empty("player"), 1_000L + sessionId);
        }

        assertTrue(store.consume(1L, 2_000L).isEmpty(), "the oldest frame is evicted once the cap is reached");
        assertTrue(store.consume(100L, 2_000L).isPresent(), "the newest frame is still available");
    }
}
