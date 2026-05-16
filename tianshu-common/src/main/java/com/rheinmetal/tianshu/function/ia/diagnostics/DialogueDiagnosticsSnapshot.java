package com.rheinmetal.tianshu.function.ia.diagnostics;

import com.rheinmetal.tianshu.function.ia.model.DialogueParticipantDescriptor;
import com.rheinmetal.tianshu.function.ia.model.DialogueSession;

import java.util.List;

public record DialogueDiagnosticsSnapshot(List<DialogueParticipantDescriptor> participants, List<DialogueSession> sessions) {
    public DialogueDiagnosticsSnapshot {
        participants = participants == null ? List.of() : List.copyOf(participants);
        sessions = sessions == null ? List.of() : List.copyOf(sessions);
    }
}
