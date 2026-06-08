package com.rheinmetal.tianshu.function.ia.control;

import com.rheinmetal.tianshu.function.ia.model.DialogueReleaseReason;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogueSessionControlPolicyTest {
    @Test
    void allowsOwnerControlForActiveSessionBeforeProcessingDeadline() {
        DialogueSessionControlPolicy policy = new DialogueSessionControlPolicy();

        DialogueSessionControlDecision decision = policy.decide(session(DialogueSessionState.ACTIVE, 1_000L, null), DialogueSessionControlAction.RELEASE, 100L);

        assertTrue(decision.allowed());
    }

    @Test
    void deniesTerminalSessionControl() {
        DialogueSessionControlPolicy policy = new DialogueSessionControlPolicy();

        DialogueSessionControlDecision decision = policy.decide(session(DialogueSessionState.RELEASED, 1_000L, DialogueReleaseReason.OWNER_COMPLETED), DialogueSessionControlAction.EXTEND_PROCESSING, 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_TERMINAL", decision.reasonCode());
    }

    @Test
    void deniesExpiredProcessingDeadlineControl() {
        DialogueSessionControlPolicy policy = new DialogueSessionControlPolicy();

        DialogueSessionControlDecision decision = policy.decide(session(DialogueSessionState.ACTIVE, 100L, null), DialogueSessionControlAction.RELEASE, 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_PROCESSING_DEADLINE_EXPIRED", decision.reasonCode());
    }

    @Test
    void deniesProcessingExtensionWhenInterrupting() {
        DialogueSessionControlPolicy policy = new DialogueSessionControlPolicy();

        DialogueSessionControlDecision decision = policy.decide(session(DialogueSessionState.INTERRUPTING, 1_000L, null), DialogueSessionControlAction.EXTEND_PROCESSING, 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_PROCESSING_EXTENSION_NOT_ALLOWED", decision.reasonCode());
    }

    @Test
    void deniesInterruptAckWhenClaimed() {
        DialogueSessionControlPolicy policy = new DialogueSessionControlPolicy();

        DialogueSessionControlDecision decision = policy.decide(session(DialogueSessionState.CLAIMED, 1_000L, null), DialogueSessionControlAction.INTERRUPT_ACK, 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_INTERRUPT_NOT_ALLOWED", decision.reasonCode());
    }

    private DialogueSession session(DialogueSessionState state, long processingDeadlineMillis, DialogueReleaseReason releaseReason) {
        return new DialogueSession("session", "player", "module.owner", "participant.owner", state, "turn", 100L, 100L, processingDeadlineMillis, releaseReason);
    }
}
