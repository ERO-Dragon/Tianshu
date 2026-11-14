package com.rheinmetal.tianshu.function.auxilium.core.turn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The gate's second duty: keep a reply that may still be a tool call out of the speaker. */
class AXReplyStreamGateJsonTest {

    @Test
    void aToolCallIsHeldChunkByChunkAndNeverSpoken() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        String reply = "{\"tool\":\"test:echo\",\"args\":{\"text\":\"hi\"}}";

        for (int index = 0; index < reply.length(); index += 7) {
            String spoken = gate.accept(reply.substring(index, Math.min(reply.length(), index + 7)));
            assertTrue(spoken.isEmpty(), "nothing of a possible call may be spoken");
        }
        assertTrue(gate.holding());
        assertEquals("", gate.emitted());

        gate.discardHeld();
        assertFalse(gate.holding());
        assertEquals("", gate.emitted());
        assertEquals("", gate.flush());
    }

    @Test
    void aHeldReplyThatTurnsOutToBeAnAnswerIsReleasedOnFlush() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        assertTrue(gate.accept("```json\n[1,2]").isEmpty());
        assertTrue(gate.holding());
        assertEquals("```json\n[1,2]", gate.flush());
    }

    @Test
    void leadingWhitespaceDoesNotHideTheStart() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        assertTrue(gate.accept("  \n{\"tool\":\"x:y\"}").isEmpty());
        assertTrue(gate.holding());
    }

    @Test
    void ordinaryTextStillStreamsImmediately() {
        AXReplyStreamGate gate = new AXReplyStreamGate();
        assertEquals("You have", gate.accept("You have"));
        assertEquals(" three sticks.", gate.accept(" three sticks."));
        assertFalse(gate.holding());
        assertEquals("", gate.flush());
    }
}