package com.rheinmetal.tianshu.function.auxilium.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input type's own invariants, independent of where the turn came from.
 *
 * <p>Identity is the part that used to be borrowed from the intake side, so it is the part worth pinning:
 * every turn has a turn id, a typed turn's session belongs to the player rather than to a device, and a typed
 * turn carries no recognized objects because nothing recognized anything.</p>
 */
class AXDialogueInputTest {

    @Test
    void everyTurnHasATurnIdEvenWhenNoneIsGiven() {
        AXDialogueInput input = new AXDialogueInput(
                "player", "session", "  ", "hello", null, null, null, null, null, 0L);

        assertFalse(input.turnId().isBlank(), "a turn without an id has no place in the history");
    }

    @Test
    void aDerivedTurnIdIsStableForTheSameUtteranceInTheSameSession() {
        AXDialogueInput first = new AXDialogueInput("player", "session", "", "hello", null, null, null, null, null, 0L);
        AXDialogueInput again = new AXDialogueInput("player", "session", "", "hello", null, null, null, null, null, 0L);
        AXDialogueInput other = new AXDialogueInput("player", "session", "", "goodbye", null, null, null, null, null, 0L);

        assertEquals(first.turnId(), again.turnId());
        assertNotEquals(first.turnId(), other.turnId());
    }

    @Test
    void aTypedTurnKeepsOneSessionPerPlayerAndOpensANewTurnEachTime() {
        AXDialogueInput first = AXDialogueInput.typed("player-1", "第一个问题", 0L);
        AXDialogueInput second = AXDialogueInput.typed("player-1", "第二个问题", 0L);

        assertEquals(first.sessionId(), second.sessionId(), "typed turns of one player read as one conversation");
        assertNotEquals(first.turnId(), second.turnId(), "two utterances are two turns");
        assertTrue(first.sessionId().startsWith("ax.text."), "typed sessions stay separate from spoken ones");
    }

    @Test
    void aTypedTurnCarriesNoRecognizedObjects() {
        AXDialogueInput input = AXDialogueInput.typed("player-1", "钻石锄怎么用", 0L);

        assertTrue(input.objectMatches().isEmpty(), "nothing was recognized, so nothing was matched");
        assertEquals("钻石锄怎么用", input.text(), "typed text is used exactly as the player wrote it");
    }

    @Test
    void anExpiredDeadlineIsDetectedAndAZeroDeadlineNeverExpires() {
        AXDialogueInput noDeadline = AXDialogueInput.typed("player", "hello", 0L);
        AXDialogueInput past = AXDialogueInput.typed("player", "hello", 1L);

        assertFalse(noDeadline.isExpired(Long.MAX_VALUE));
        assertTrue(past.isExpired(System.currentTimeMillis()));
    }
}
