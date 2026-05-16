package com.rheinmetal.tianshu.function.ia.control;

import com.rheinmetal.tianshu.function.ia.model.DialogueSession;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionControlAction;
import com.rheinmetal.tianshu.function.ia.model.DialogueSessionState;

public final class DialogueSessionControlPolicy {
    public DialogueSessionControlDecision decide(DialogueSession session, DialogueSessionControlAction action, long nowMillis) {
        if (session == null) {
            return DialogueSessionControlDecision.deny("SESSION_MISSING", "Dialogue session is missing");
        }
        DialogueSessionControlAction effectiveAction = action == null ? DialogueSessionControlAction.RELEASE : action;
        if (terminal(session.state())) {
            return DialogueSessionControlDecision.deny("SESSION_TERMINAL", "Dialogue session is already terminal");
        }
        if (session.leaseExpireAtMillis() <= nowMillis) {
            return DialogueSessionControlDecision.deny("SESSION_LEASE_EXPIRED", "Dialogue session lease has expired");
        }
        if (effectiveAction == DialogueSessionControlAction.RENEW && session.state() != DialogueSessionState.ACTIVE && session.state() != DialogueSessionState.CLAIMED) {
            return DialogueSessionControlDecision.deny("SESSION_RENEW_NOT_ALLOWED", "Dialogue session cannot be renewed in current state");
        }
        if (effectiveAction == DialogueSessionControlAction.INTERRUPT_ACK && session.state() != DialogueSessionState.ACTIVE && session.state() != DialogueSessionState.INTERRUPTING) {
            return DialogueSessionControlDecision.deny("SESSION_INTERRUPT_NOT_ALLOWED", "Dialogue session cannot be interrupted in current state");
        }
        return DialogueSessionControlDecision.allow();
    }

    private boolean terminal(DialogueSessionState state) {
        return state == DialogueSessionState.RELEASED || state == DialogueSessionState.EXPIRED || state == DialogueSessionState.REJECTED;
    }
}
