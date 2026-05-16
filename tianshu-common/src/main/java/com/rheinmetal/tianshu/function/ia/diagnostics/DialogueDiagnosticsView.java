package com.rheinmetal.tianshu.function.ia.diagnostics;

import com.rheinmetal.tianshu.function.ia.registry.DialogueParticipantRegistry;
import com.rheinmetal.tianshu.function.ia.session.DialogueSessionStore;

import java.util.Objects;

public final class DialogueDiagnosticsView {
    private final DialogueParticipantRegistry participantRegistry;
    private final DialogueSessionStore sessionStore;

    public DialogueDiagnosticsView(DialogueParticipantRegistry participantRegistry, DialogueSessionStore sessionStore) {
        this.participantRegistry = Objects.requireNonNull(participantRegistry, "participantRegistry");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    public DialogueDiagnosticsSnapshot snapshot() {
        return new DialogueDiagnosticsSnapshot(participantRegistry.snapshot(), sessionStore.snapshot());
    }
}
