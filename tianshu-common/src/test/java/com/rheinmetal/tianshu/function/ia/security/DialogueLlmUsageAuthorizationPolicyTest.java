package com.rheinmetal.tianshu.function.ia.security;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueLlmUsageAuthorizationPolicyTest {
    @Test
    void allowsCurrentOwnerForActiveSession() {
        DialogueLlmUsageAuthorizationPolicy policy = new DialogueLlmUsageAuthorizationPolicy(new DialogueAccessController());

        DialogueAccessDecision decision = policy.authorize(session(DialogueSessionState.ACTIVE, "turn", 1_000L), "module.owner", "participant.owner", "turn", 100L);

        assertTrue(decision.allowed());
    }

    @Test
    void deniesRequesterThatIsNotOwner() {
        DialogueLlmUsageAuthorizationPolicy policy = new DialogueLlmUsageAuthorizationPolicy(new DialogueAccessController());

        DialogueAccessDecision decision = policy.authorize(session(DialogueSessionState.ACTIVE, "turn", 1_000L), "module.other", "participant.other", "turn", 100L);

        assertFalse(decision.allowed());
        assertEquals("NOT_SESSION_OWNER", decision.reasonCode());
    }

    @Test
    void deniesExpiredSessionLease() {
        DialogueLlmUsageAuthorizationPolicy policy = new DialogueLlmUsageAuthorizationPolicy(new DialogueAccessController());

        DialogueAccessDecision decision = policy.authorize(session(DialogueSessionState.ACTIVE, "turn", 100L), "module.owner", "participant.owner", "turn", 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_NOT_ACTIVE", decision.reasonCode());
    }

    @Test
    void deniesTerminalSession() {
        DialogueLlmUsageAuthorizationPolicy policy = new DialogueLlmUsageAuthorizationPolicy(new DialogueAccessController());

        DialogueAccessDecision decision = policy.authorize(session(DialogueSessionState.RELEASED, "turn", 1_000L), "module.owner", "participant.owner", "turn", 100L);

        assertFalse(decision.allowed());
        assertEquals("SESSION_NOT_ACTIVE", decision.reasonCode());
    }

    @Test
    void deniesTurnMismatch() {
        DialogueLlmUsageAuthorizationPolicy policy = new DialogueLlmUsageAuthorizationPolicy(new DialogueAccessController());

        DialogueAccessDecision decision = policy.authorize(session(DialogueSessionState.ACTIVE, "turn.one", 1_000L), "module.owner", "participant.owner", "turn.two", 100L);

        assertFalse(decision.allowed());
        assertEquals("TURN_MISMATCH", decision.reasonCode());
    }

    private DialogueSession session(DialogueSessionState state, String turnId, long leaseExpireAtMillis) {
        return new DialogueSession("session", "player", "module.owner", "participant.owner", state, turnId, 100L, 100L, leaseExpireAtMillis, null);
    }
}
